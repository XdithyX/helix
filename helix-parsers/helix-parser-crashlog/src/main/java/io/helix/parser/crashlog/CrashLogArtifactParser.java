package io.helix.parser.crashlog;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.TimelineEvent;
import io.helix.core.model.TimelineEventType;
import io.helix.core.model.crash.CrashLogData;
import io.helix.core.model.crash.CrashStackFrame;
import io.helix.core.model.crash.ProblematicFrame;
import io.helix.core.spi.ArtifactParser;
import io.helix.core.spi.ParseContext;
import io.helix.core.spi.ParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CrashLogArtifactParser implements ArtifactParser {
  public static final String CRASH_DATA_KEY = "crashLog";

  private static final Pattern SIGNAL = Pattern.compile("#\\s+(SIG[A-Z0-9]+)\\s*\\(([^)]*)\\)");
  private static final Pattern WINDOWS_EXCEPTION = Pattern.compile("#\\s+(EXCEPTION_[A-Z_]+)\\s*\\(([^)]*)\\)");
  private static final Pattern PROBLEMATIC_FRAME = Pattern.compile("#\\s+Problematic frame:\\s*");
  private static final Pattern FRAME =
      Pattern.compile("#?\\s*([CVJj])\\s+\\[([^+\\]]+)(?:\\+([^\\]]+))?\\]\\s*(.*)");
  private static final Pattern REGISTER =
      Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9]+)=((?:0x)?[0-9a-fA-F`]+)");

  @Override
  public boolean canParse(Path path, ArtifactType hint) {
    if (hint != null) {
      return hint == ArtifactType.CRASH_LOG;
    }
    if (path == null || path.getFileName() == null) {
      return false;
    }
    String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return fileName.startsWith("hs_err_pid") && fileName.endsWith(".log");
  }

  @Override
  public ParsedArtifact parse(Path path, ParseContext ctx) throws ParseException {
    if (!Files.isRegularFile(path)) {
      throw new ParseException("Crash log file does not exist or is not a regular file: " + path);
    }

    Path normalized = path.toAbsolutePath().normalize();
    try {
      ctx.progressMonitor().beginTask("Parse crash log", Files.size(normalized));
      String text = Files.readString(normalized).replace("\r\n", "\n").replace('\r', '\n');
      Instant capturedAt = Files.getLastModifiedTime(normalized).toInstant();
      ParsedCrashLog parsed = parseText(text);
      CrashLogData data = parsed.data();
      Artifact artifact = new Artifact(
          artifactId(normalized),
          ArtifactType.CRASH_LOG,
          capturedAt,
          Map.of(
              "filename", normalized.getFileName().toString(),
              "size.bytes", Long.toString(Files.size(normalized)),
              "parser", "helix-crash-log"),
          JvmFlavour.HOTSPOT,
          normalized);
      ctx.progressMonitor().complete();
      return new ParsedArtifact(
          artifact,
          timelineEvents(artifact.id(), capturedAt, data),
          Map.of(CRASH_DATA_KEY, data),
          parsed.warnings());
    } catch (IOException e) {
      throw new ParseException("Failed to read crash log file: " + normalized, e);
    } finally {
      ctx.progressMonitor().complete();
    }
  }

  @Override
  public ArtifactType artifactType() {
    return ArtifactType.CRASH_LOG;
  }

  static ParsedCrashLog parseText(String text) {
    String signal = "";
    String exceptionCode = "";
    ProblematicFrame problematicFrame = new ProblematicFrame("", "", "", "", "");
    String currentThread = "";
    String jvmVersion = "";
    String jvmState = "";
    List<String> heapInfo = new ArrayList<>();
    List<String> vmFlags = new ArrayList<>();
    Map<String, String> environment = new LinkedHashMap<>();
    List<CrashStackFrame> stackFrames = new ArrayList<>();
    List<String> compiledMethods = new ArrayList<>();
    List<String> dynamicLibraries = new ArrayList<>();
    Map<String, String> registers = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    boolean oomInHeader = text.contains("OutOfMemoryError") || text.contains("Out of Memory Error");
    boolean expectProblematicFrame = false;
    Section section = Section.HEADER;

    for (String line : text.split("\n", -1)) {
      String trimmed = line.trim();
      Matcher signalMatcher = SIGNAL.matcher(trimmed);
      Matcher exceptionMatcher = WINDOWS_EXCEPTION.matcher(trimmed);
      if (signalMatcher.find()) {
        signal = signalMatcher.group(1);
        exceptionCode = signalMatcher.group(2);
      } else if (exceptionMatcher.find()) {
        signal = exceptionMatcher.group(1);
        exceptionCode = exceptionMatcher.group(2);
      }

      if (PROBLEMATIC_FRAME.matcher(trimmed).matches()) {
        expectProblematicFrame = true;
        continue;
      }
      if (expectProblematicFrame && !trimmed.isBlank()) {
        problematicFrame = parseProblematicFrame(trimmed);
        expectProblematicFrame = false;
      }

      if (trimmed.startsWith("Current thread")) {
        currentThread = valueAfterColon(trimmed);
      } else if (trimmed.startsWith("Java VM:")) {
        jvmVersion = valueAfterColon(trimmed);
      } else if (trimmed.startsWith("VM state:")) {
        jvmState = valueAfterColon(trimmed);
      }

      section = sectionFor(trimmed, section);
      switch (section) {
        case NATIVE_FRAMES -> parseFrame(trimmed).ifPresent(stackFrames::add);
        case HEAP -> {
          if (!trimmed.isBlank() && !trimmed.startsWith("Heap:")) {
            heapInfo.add(trimmed);
          }
        }
        case VM_FLAGS -> {
          if (!trimmed.isBlank() && !trimmed.startsWith("VM Arguments")) {
            vmFlags.add(trimmed);
          }
        }
        case ENVIRONMENT -> parseEnvironment(trimmed).ifPresent(entry -> environment.put(entry.key(), entry.value()));
        case COMPILED_METHODS -> {
          if (!trimmed.isBlank() && !trimmed.startsWith("Compiled method")) {
            compiledMethods.add(trimmed);
          }
        }
        case DYNAMIC_LIBRARIES -> {
          if (looksLikeLibrary(trimmed)) {
            dynamicLibraries.add(trimmed);
          }
        }
        case REGISTERS -> parseRegisters(trimmed, registers);
        default -> {
        }
      }
    }

    if (signal.isBlank()) {
      warnings.add("Crash signal or exception code was not recognized.");
    }
    if (problematicFrame.raw().isBlank()) {
      warnings.add("Problematic frame was not recognized.");
    }
    CrashLogData data = new CrashLogData(
        signal,
        exceptionCode,
        problematicFrame,
        currentThread,
        jvmVersion,
        jvmState,
        heapInfo,
        vmFlags,
        environment,
        stackFrames,
        compiledMethods,
        dynamicLibraries,
        registers,
        oomInHeader,
        mapOf("parser", "helix-crash-log"));
    return new ParsedCrashLog(data, warnings);
  }

  private static List<TimelineEvent> timelineEvents(String artifactId, Instant timestamp, CrashLogData data) {
    List<TimelineEvent> events = new ArrayList<>();
    events.add(new TimelineEvent(
        timestamp,
        artifactId,
        TimelineEventType.NATIVE_CRASH,
        null,
        mapOf(
            "signal", data.signal(),
            "exception", data.exceptionCode(),
            "library", data.problematicFrame().library(),
            "symbol", data.problematicFrame().symbol())));
    if (data.oomInHeader()) {
      events.add(new TimelineEvent(
          timestamp,
          artifactId,
          TimelineEventType.OOM,
          null,
          mapOf("source", "hs_err_pid", "signal", data.signal())));
    }
    return events;
  }

  private static ProblematicFrame parseProblematicFrame(String line) {
    CrashStackFrame frame = parseFrame(line).orElse(new CrashStackFrame("", "", "", "", line));
    return new ProblematicFrame(frame.kind(), frame.library(), frame.symbol(), frame.address(), line);
  }

  private static Optional<CrashStackFrame> parseFrame(String line) {
    Matcher matcher = FRAME.matcher(line);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(new CrashStackFrame(
        matcher.group(1),
        matcher.group(2),
        matcher.group(4),
        matcher.group(3) == null ? "" : matcher.group(3),
        line));
  }

  private static Section sectionFor(String line, Section current) {
    if (line.startsWith("Native frames:") || line.startsWith("Stack:")) {
      return Section.NATIVE_FRAMES;
    }
    if (line.startsWith("Registers:")) {
      return Section.REGISTERS;
    }
    if (line.startsWith("Heap:")) {
      return Section.HEAP;
    }
    if (line.startsWith("VM Arguments:")) {
      return Section.VM_FLAGS;
    }
    if (line.startsWith("Environment Variables:") || line.startsWith("OS:") || line.startsWith("CPU:")) {
      return Section.ENVIRONMENT;
    }
    if (line.startsWith("Compiled method")) {
      return Section.COMPILED_METHODS;
    }
    if (line.startsWith("Dynamic libraries:")) {
      return Section.DYNAMIC_LIBRARIES;
    }
    if (line.startsWith("---------------") || line.startsWith("END.")) {
      return Section.HEADER;
    }
    return current;
  }

  private static Optional<Entry> parseEnvironment(String line) {
    if (line.isBlank()) {
      return Optional.empty();
    }
    int colon = line.indexOf(':');
    int equals = line.indexOf('=');
    int separator = colon >= 0 ? colon : equals;
    if (separator <= 0) {
      return Optional.empty();
    }
    return Optional.of(new Entry(line.substring(0, separator).trim(), line.substring(separator + 1).trim()));
  }

  private static void parseRegisters(String line, Map<String, String> registers) {
    Matcher matcher = REGISTER.matcher(line);
    while (matcher.find()) {
      registers.put(matcher.group(1), matcher.group(2));
    }
  }

  private static boolean looksLikeLibrary(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    return lower.contains(".so")
        || lower.contains(".dylib")
        || lower.contains(".dll")
        || lower.contains("lib");
  }

  private static String valueAfterColon(String line) {
    int colon = line.indexOf(':');
    return colon < 0 ? "" : line.substring(colon + 1).trim();
  }

  private static String artifactId(Path path) throws IOException {
    String fileName = path.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "-");
    long size = Files.size(path);
    Instant modified = Files.getLastModifiedTime(path).toInstant();
    return "crash-" + fileName + "-" + Long.toHexString((fileName + size + modified).hashCode());
  }

  private static Map<String, Object> mapOf(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      Object value = values[i + 1];
      if (value != null) {
        result.put((String) values[i], value);
      }
    }
    return result;
  }

  record ParsedCrashLog(CrashLogData data, List<String> warnings) {
    ParsedCrashLog {
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }

  private record Entry(String key, String value) {}

  private enum Section {
    HEADER,
    NATIVE_FRAMES,
    REGISTERS,
    HEAP,
    VM_FLAGS,
    ENVIRONMENT,
    COMPILED_METHODS,
    DYNAMIC_LIBRARIES
  }
}
