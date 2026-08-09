package io.helix.parser.threaddump;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.StackFrame;
import io.helix.core.model.TimelineEvent;
import io.helix.core.model.TimelineEventType;
import io.helix.core.model.thread.JavaThreadState;
import io.helix.core.model.thread.ThreadDumpData;
import io.helix.core.model.thread.ThreadDumpFormat;
import io.helix.core.model.thread.ThreadDumpSnapshot;
import io.helix.core.model.thread.ThreadLock;
import io.helix.core.model.thread.ThreadSample;
import io.helix.core.spi.ArtifactParser;
import io.helix.core.spi.ParseContext;
import io.helix.core.spi.ParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ThreadDumpArtifactParser implements ArtifactParser {
  public static final String THREAD_DATA_KEY = "threadDump";

  private static final Pattern HOTSPOT_HEADER = Pattern.compile("^\"([^\"]+)\"(.*)$");
  private static final Pattern HOTSPOT_STATE =
      Pattern.compile("java\\.lang\\.Thread\\.State:\\s+([A-Z_]+)");
  private static final Pattern HOTSPOT_LOCK =
      Pattern.compile("-\\s+(waiting to lock|waiting on|parking to wait for|locked)\\s+<([^>]+)>\\s+\\(a\\s+([^\\)]+)\\)");
  private static final Pattern HOTSPOT_OWNABLE_SYNCHRONIZER =
      Pattern.compile("-\\s+<([^>]+)>\\s+\\(a\\s+([^\\)]+)\\)");
  private static final Pattern OPENJ9_THREAD =
      Pattern.compile("^3XMTHREADINFO\\s+\"([^\"]+)\"(.*)$");
  private static final Pattern OPENJ9_BLOCKED =
      Pattern.compile(".*Blocked on:\\s+([^@\\s]+)@([^\\s]+).*");
  private static final Pattern QUOTED_VALUE = Pattern.compile("\"([^\"]+)\"");
  private static final Pattern ISO_INSTANT =
      Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z).*$");
  private static final Pattern LOCAL_TIMESTAMP =
      Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}).*$");

  @Override
  public boolean canParse(Path path, ArtifactType hint) {
    if (hint != null) {
      return hint == ArtifactType.THREAD_DUMP;
    }
    if (path == null || path.getFileName() == null) {
      return false;
    }
    String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return fileName.endsWith(".tdump")
        || fileName.endsWith(".threaddump")
        || fileName.endsWith(".thread")
        || fileName.endsWith(".threads")
        || fileName.endsWith(".txt");
  }

  @Override
  public ParsedArtifact parse(Path path, ParseContext ctx) throws ParseException {
    if (!Files.isRegularFile(path)) {
      throw new ParseException("Thread dump file does not exist or is not a regular file: " + path);
    }

    Path normalized = path.toAbsolutePath().normalize();
    try {
      ctx.progressMonitor().beginTask("Parse thread dump", Files.size(normalized));
      String text = Files.readString(normalized);
      Instant fallbackCapturedAt = Files.getLastModifiedTime(normalized).toInstant();
      ParsedThreadDump parsed = parseText(text, fallbackCapturedAt);
      ThreadDumpData data = new ThreadDumpData(
          parsed.snapshots(),
          parsed.format(),
          Map.of(
              "parser", "helix-thread-dump",
              "snapshot.count", parsed.snapshots().size(),
              "thread.count", parsed.snapshots().stream().mapToLong(snapshot -> snapshot.threads().size()).sum()));
      Artifact artifact = new Artifact(
          artifactId(normalized),
          ArtifactType.THREAD_DUMP,
          parsed.snapshots().isEmpty() ? fallbackCapturedAt : parsed.snapshots().getFirst().capturedAt(),
          Map.of(
              "filename", normalized.getFileName().toString(),
              "size.bytes", Long.toString(Files.size(normalized)),
              "parser", "helix-thread-dump",
              "thread.format", parsed.format().name()),
          jvmFlavour(parsed.format()),
          normalized);
      ctx.progressMonitor().complete();
      return new ParsedArtifact(
          artifact,
          timelineEvents(artifact.id(), parsed.snapshots()),
          Map.of(THREAD_DATA_KEY, data),
          parsed.warnings());
    } catch (IOException e) {
      throw new ParseException("Failed to read thread dump file: " + normalized, e);
    } finally {
      ctx.progressMonitor().complete();
    }
  }

  @Override
  public ArtifactType artifactType() {
    return ArtifactType.THREAD_DUMP;
  }

  static ParsedThreadDump parseText(String text, Instant fallbackCapturedAt) {
    String normalizedText = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    if (normalizedText.contains("3XMTHREADINFO")) {
      return parseOpenJ9(normalizedText, fallbackCapturedAt);
    }
    ParsedThreadDump hotSpot = parseHotSpot(normalizedText, fallbackCapturedAt);
    if (!hotSpot.snapshots().isEmpty()) {
      return hotSpot;
    }
    return parseJfrText(normalizedText, fallbackCapturedAt, hotSpot.warnings());
  }

  private static ParsedThreadDump parseHotSpot(String text, Instant fallbackCapturedAt) {
    List<ThreadDumpSnapshot> snapshots = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    List<ThreadSample> threads = new ArrayList<>();
    MutableThread current = null;
    boolean inOwnableSynchronizers = false;
    Instant pendingTimestamp = null;
    Instant snapshotTimestamp = fallbackCapturedAt;
    int lineNumber = 0;

    for (String line : text.split("\n", -1)) {
      lineNumber++;
      if (line.startsWith("Found one Java-level deadlock:")
          || line.matches("^Found \\d+ Java-level deadlocks?:.*")) {
        break;
      }
      Optional<Instant> timestamp = parseTimestamp(line);
      if (timestamp.isPresent()) {
        pendingTimestamp = timestamp.get();
        if (!threads.isEmpty() || current != null) {
          current = finishThread(current, threads, warnings);
          snapshots.add(snapshot(snapshotTimestamp, ThreadDumpFormat.HOTSPOT, threads));
          threads = new ArrayList<>();
          snapshotTimestamp = pendingTimestamp;
        }
        continue;
      }

      if (line.startsWith("Full thread dump")) {
        if (!threads.isEmpty() || current != null) {
          current = finishThread(current, threads, warnings);
          snapshots.add(snapshot(snapshotTimestamp, ThreadDumpFormat.HOTSPOT, threads));
          threads = new ArrayList<>();
        }
        snapshotTimestamp = pendingTimestamp == null ? fallbackCapturedAt : pendingTimestamp;
        pendingTimestamp = null;
        continue;
      }

      Matcher header = HOTSPOT_HEADER.matcher(line);
      if (header.matches()) {
        current = finishThread(current, threads, warnings);
        current = new MutableThread(header.group(1), line);
        inOwnableSynchronizers = false;
        current.javaThreadId = findToken(line, "#([0-9]+)").orElse("");
        current.osThreadId = findToken(line, "\\bnid=([^\\s]+)").orElse("");
        current.cpuTimeMillis = parseCpuMillis(line);
        continue;
      }

      if (current == null) {
        continue;
      }

      String trimmed = line.trim();
      if (trimmed.equals("Locked ownable synchronizers:")) {
        inOwnableSynchronizers = true;
        continue;
      }

      if (inOwnableSynchronizers) {
        if (trimmed.equals("- None") || trimmed.isEmpty()) {
          continue;
        }
        Matcher synchronizer = HOTSPOT_OWNABLE_SYNCHRONIZER.matcher(trimmed);
        if (synchronizer.matches()) {
          current.heldLocks.add(new ThreadLock(
              synchronizer.group(1),
              synchronizer.group(2),
              current.name,
              "locked ownable synchronizer"));
          continue;
        }
        inOwnableSynchronizers = false;
      }

      Matcher state = HOTSPOT_STATE.matcher(trimmed);
      if (state.find()) {
        current.state = parseState(state.group(1));
        continue;
      }

      Matcher lock = HOTSPOT_LOCK.matcher(trimmed);
      if (lock.matches()) {
        ThreadLock threadLock = new ThreadLock(lock.group(2), lock.group(3), "", lock.group(1));
        switch (lock.group(1)) {
          case "locked" -> current.heldLocks.add(threadLock);
          case "waiting to lock" -> current.waitingToLock = threadLock;
          default -> current.waitingOnLock = threadLock;
        }
        continue;
      }

      if (trimmed.startsWith("at ")) {
        current.stackTrace.add(parseStackFrame(trimmed.substring(3)));
      } else if (trimmed.startsWith("- ")) {
        warnings.add("Ignored unrecognized lock line " + lineNumber + ": " + trimmed);
      }
    }

    finishThread(current, threads, warnings);
    if (!threads.isEmpty()) {
      snapshots.add(snapshot(snapshotTimestamp, ThreadDumpFormat.HOTSPOT, threads));
    }
    if (snapshots.isEmpty() && !text.isBlank()) {
      warnings.add("No HotSpot thread blocks were recognized.");
    }
    return new ParsedThreadDump(snapshots, ThreadDumpFormat.HOTSPOT, warnings);
  }

  private static ParsedThreadDump parseOpenJ9(String text, Instant fallbackCapturedAt) {
    List<ThreadDumpSnapshot> snapshots = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    List<ThreadSample> threads = new ArrayList<>();
    MutableThread current = null;
    Instant snapshotTimestamp = fallbackCapturedAt;

    for (String line : text.split("\n", -1)) {
      Optional<Instant> timestamp = parseTimestamp(line);
      if (timestamp.isPresent()) {
        snapshotTimestamp = timestamp.get();
        continue;
      }

      if (line.startsWith("0SECTION") && (!threads.isEmpty() || current != null)) {
        current = finishThread(current, threads, warnings);
        snapshots.add(snapshot(snapshotTimestamp, ThreadDumpFormat.OPENJ9, threads));
        threads = new ArrayList<>();
        continue;
      }

      Matcher thread = OPENJ9_THREAD.matcher(line);
      if (thread.matches()) {
        current = finishThread(current, threads, warnings);
        current = new MutableThread(thread.group(1), line);
        current.state = parseOpenJ9State(findToken(line, "\\bstate:([^,\\s]+)").orElse(""));
        current.javaThreadId = findToken(line, "java/lang/Thread:([^,\\s]+)").orElse("");
        continue;
      }

      if (current == null) {
        continue;
      }

      String trimmed = line.trim();
      if (trimmed.startsWith("3XMTHREADINFO1")) {
        current.osThreadId = findToken(trimmed, "native thread ID:([^,\\s]+)").orElse(current.osThreadId);
        current.state = parseOpenJ9State(findToken(trimmed, "\\bvmstate:([^,\\s]+)").orElse(current.state.name()));
      } else if (trimmed.startsWith("4XESTACKTRACE")) {
        int at = trimmed.indexOf("at ");
        if (at >= 0) {
          current.stackTrace.add(parseStackFrame(trimmed.substring(at + 3)));
        }
      } else if (trimmed.startsWith("3XMTHREADBLOCK")) {
        Matcher blocked = OPENJ9_BLOCKED.matcher(trimmed);
        if (blocked.matches()) {
          String owner = findToken(trimmed, "Owned by:\\s+\"([^\"]+)\"").orElse("");
          current.waitingToLock = new ThreadLock(
              blocked.group(2),
              blocked.group(1).replace('/', '.'),
              owner,
              "blocked");
          current.state = JavaThreadState.BLOCKED;
        }
      }
    }

    finishThread(current, threads, warnings);
    if (!threads.isEmpty()) {
      snapshots.add(snapshot(snapshotTimestamp, ThreadDumpFormat.OPENJ9, threads));
    }
    if (snapshots.isEmpty()) {
      warnings.add("No OpenJ9 thread blocks were recognized.");
    }
    return new ParsedThreadDump(snapshots, ThreadDumpFormat.OPENJ9, warnings);
  }

  private static ParsedThreadDump parseJfrText(
      String text,
      Instant fallbackCapturedAt,
      List<String> previousWarnings) {
    List<ThreadSample> threads = new ArrayList<>();
    List<String> warnings = new ArrayList<>(previousWarnings);
    String currentName = "";
    JavaThreadState currentState = JavaThreadState.UNKNOWN;

    for (String line : text.split("\n", -1)) {
      String trimmed = line.trim();
      if (trimmed.startsWith("eventThread =")) {
        Matcher quoted = QUOTED_VALUE.matcher(trimmed);
        currentName = quoted.find() ? quoted.group(1) : trimmed.substring(trimmed.indexOf('=') + 1).trim();
      } else if (trimmed.startsWith("state =")) {
        String value = trimmed.substring(trimmed.indexOf('=') + 1).replace("\"", "").trim();
        currentState = parseState(value);
      } else if (trimmed.isBlank() && !currentName.isBlank()) {
        threads.add(new ThreadSample(
            currentName,
            "",
            "",
            currentState,
            List.of(),
            List.of(),
            null,
            null,
            0,
            "JFR text event"));
        currentName = "";
        currentState = JavaThreadState.UNKNOWN;
      }
    }
    if (!currentName.isBlank()) {
      threads.add(new ThreadSample(currentName, "", "", currentState, List.of(), List.of(), null, null, 0, "JFR text event"));
    }
    if (threads.isEmpty()) {
      warnings.add("No JFR text thread state events were recognized.");
      return new ParsedThreadDump(List.of(), ThreadDumpFormat.UNKNOWN, warnings);
    }
    return new ParsedThreadDump(
        List.of(snapshot(fallbackCapturedAt, ThreadDumpFormat.JFR_TEXT, threads)),
        ThreadDumpFormat.JFR_TEXT,
        warnings);
  }

  private static MutableThread finishThread(
      MutableThread current,
      List<ThreadSample> threads,
      List<String> warnings) {
    if (current == null) {
      return null;
    }
    if (current.stackTrace.isEmpty() && current.state == JavaThreadState.UNKNOWN) {
      warnings.add("Thread '" + current.name + "' has no recognized state or stack frames.");
    }
    threads.add(current.toSample());
    return null;
  }

  private static ThreadDumpSnapshot snapshot(
      Instant capturedAt,
      ThreadDumpFormat format,
      List<ThreadSample> threads) {
    return new ThreadDumpSnapshot(
        capturedAt,
        format,
        threads,
        Map.of("thread.count", threads.size()));
  }

  private static List<TimelineEvent> timelineEvents(
      String artifactId,
      List<ThreadDumpSnapshot> snapshots) {
    List<TimelineEvent> events = new ArrayList<>();
    for (ThreadDumpSnapshot snapshot : snapshots) {
      for (ThreadSample thread : snapshot.threads()) {
        ThreadLock waitingLock = thread.waitingLock();
        if (thread.state() == JavaThreadState.BLOCKED || thread.waitingToLock() != null) {
          events.add(new TimelineEvent(
              snapshot.capturedAt(),
              artifactId,
              TimelineEventType.THREAD_BLOCKED,
              null,
              Map.of(
                  "thread", thread.name(),
                  "state", thread.state().name(),
                  "lock", waitingLock == null ? "" : waitingLock.displayName(),
                  "owner", waitingLock == null ? "" : waitingLock.ownerThreadName())));
        }
      }
    }
    return events;
  }

  private static StackFrame parseStackFrame(String text) {
    String frame = text.trim();
    int open = frame.indexOf('(');
    int close = frame.lastIndexOf(')');
    String methodPart = open >= 0 ? frame.substring(0, open) : frame;
    String location = open >= 0 && close > open ? frame.substring(open + 1, close) : "";
    String moduleName = "";
    int moduleSeparator = methodPart.indexOf('/');
    if (moduleSeparator > 0 && methodPart.substring(0, moduleSeparator).contains(".")) {
      moduleName = methodPart.substring(0, moduleSeparator);
      methodPart = methodPart.substring(moduleSeparator + 1);
    }

    int lastDot = methodPart.lastIndexOf('.');
    String className = lastDot > 0 ? methodPart.substring(0, lastDot) : methodPart;
    String methodName = lastDot > 0 ? methodPart.substring(lastDot + 1) : "";
    className = className.replace('/', '.');

    boolean nativeFrame = location.equals("Native Method");
    int lineNumber = -1;
    String fileName = location;
    int colon = location.lastIndexOf(':');
    if (colon > 0) {
      fileName = location.substring(0, colon);
      try {
        lineNumber = Integer.parseInt(location.substring(colon + 1));
      } catch (NumberFormatException ignored) {
        lineNumber = -1;
      }
    }
    return new StackFrame(className, methodName, fileName, lineNumber, moduleName, nativeFrame);
  }

  private static JavaThreadState parseState(String value) {
    if (value == null || value.isBlank()) {
      return JavaThreadState.UNKNOWN;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    try {
      return JavaThreadState.valueOf(normalized);
    } catch (IllegalArgumentException ignored) {
      return JavaThreadState.UNKNOWN;
    }
  }

  private static JavaThreadState parseOpenJ9State(String value) {
    return switch (value == null ? "" : value.trim().toUpperCase(Locale.ROOT)) {
      case "R", "RUNNABLE" -> JavaThreadState.RUNNABLE;
      case "B", "BLOCKED" -> JavaThreadState.BLOCKED;
      case "CW", "WAITING", "WAIT", "P", "PARKED" -> JavaThreadState.WAITING;
      case "TIMED_WAITING", "TIMEDWAITING" -> JavaThreadState.TIMED_WAITING;
      default -> parseState(value);
    };
  }

  private static Optional<Instant> parseTimestamp(String line) {
    String trimmed = line == null ? "" : line.trim();
    Matcher iso = ISO_INSTANT.matcher(trimmed);
    if (iso.matches()) {
      return Optional.of(Instant.parse(iso.group(1)));
    }
    Matcher local = LOCAL_TIMESTAMP.matcher(trimmed);
    if (local.matches()) {
      String value = local.group(1).replace(' ', 'T');
      try {
        return Optional.of(LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant());
      } catch (DateTimeParseException ignored) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static Optional<String> findToken(String text, String regex) {
    Matcher matcher = Pattern.compile(regex).matcher(text);
    return matcher.find() ? Optional.ofNullable(matcher.group(1)) : Optional.empty();
  }

  private static long parseCpuMillis(String header) {
    Optional<String> cpu = findToken(header, "\\bcpu=([0-9.]+)(?:ms|s)?");
    if (cpu.isEmpty()) {
      return 0;
    }
    try {
      double value = Double.parseDouble(cpu.get());
      return header.contains("cpu=" + cpu.get() + "s") ? Math.round(value * 1_000.0) : Math.round(value);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static JvmFlavour jvmFlavour(ThreadDumpFormat format) {
    return format == ThreadDumpFormat.OPENJ9 ? JvmFlavour.OPENJ9 : JvmFlavour.HOTSPOT;
  }

  private static String artifactId(Path path) throws IOException {
    String fileName = path.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "-");
    long size = Files.size(path);
    Instant modified = Files.getLastModifiedTime(path).toInstant();
    return "threads-" + fileName + "-" + Long.toHexString((fileName + size + modified).hashCode());
  }

  record ParsedThreadDump(
      List<ThreadDumpSnapshot> snapshots,
      ThreadDumpFormat format,
      List<String> warnings) {
    ParsedThreadDump {
      snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
      format = format == null ? ThreadDumpFormat.UNKNOWN : format;
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }

  private static final class MutableThread {
    private final String name;
    private final String rawHeader;
    private String javaThreadId = "";
    private String osThreadId = "";
    private JavaThreadState state = JavaThreadState.UNKNOWN;
    private final List<StackFrame> stackTrace = new ArrayList<>();
    private final List<ThreadLock> heldLocks = new ArrayList<>();
    private ThreadLock waitingToLock;
    private ThreadLock waitingOnLock;
    private long cpuTimeMillis;

    private MutableThread(String name, String rawHeader) {
      this.name = name;
      this.rawHeader = rawHeader;
    }

    private ThreadSample toSample() {
      return new ThreadSample(
          name,
          javaThreadId,
          osThreadId,
          state,
          stackTrace,
          heldLocks,
          waitingToLock,
          waitingOnLock,
          cpuTimeMillis,
          rawHeader);
    }
  }
}
