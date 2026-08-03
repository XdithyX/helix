package io.helix.parser.gclog;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.TimelineEvent;
import io.helix.core.model.TimelineEventType;
import io.helix.core.model.gc.GcCollector;
import io.helix.core.model.gc.GcEvent;
import io.helix.core.model.gc.GcLogData;
import io.helix.core.model.gc.GcLogFormat;
import io.helix.core.spi.ArtifactParser;
import io.helix.core.spi.ParseContext;
import io.helix.core.spi.ParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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

public final class GcLogArtifactParser implements ArtifactParser {
  public static final String GC_DATA_KEY = "gcLog";

  private static final Pattern GC_ID = Pattern.compile("GC\\((\\d+)\\)\\s*(.*)");
  private static final Pattern DURATION_AT_END =
      Pattern.compile("\\s([0-9]+(?:\\.[0-9]+)?)(ms|s|secs?)\\s*$");
  private static final Pattern JDK8_DURATION =
      Pattern.compile(",\\s*([0-9]+(?:\\.[0-9]+)?)\\s*secs?\\]");
  private static final Pattern MEMORY_VALUE =
      Pattern.compile("([0-9]+(?:\\.[0-9]+)?(?:B|K|KB|M|MB|G|GB|T|TB))");
  private static final Pattern MEMORY_TRANSITION = Pattern.compile(
      "([0-9]+(?:\\.[0-9]+)?(?:B|K|KB|M|MB|G|GB|T|TB))(?:\\([^)]*\\))?->"
          + "([0-9]+(?:\\.[0-9]+)?(?:B|K|KB|M|MB|G|GB|T|TB))(?:\\(("
          + "[0-9]+(?:\\.[0-9]+)?(?:B|K|KB|M|MB|G|GB|T|TB))\\))?");
  private static final Pattern NAMED_SPACE = Pattern.compile(
      "\\b(Eden|Old|Heap)\\s*:\\s*"
          + "([0-9]+(?:\\.[0-9]+)?(?:B|K|KB|M|MB|G|GB|T|TB))(?:\\([^)]*\\))?->"
          + "([0-9]+(?:\\.[0-9]+)?(?:B|K|KB|M|MB|G|GB|T|TB))(?:\\(("
          + "[0-9]+(?:\\.[0-9]+)?(?:B|K|KB|M|MB|G|GB|T|TB))\\))?");
  private static final Pattern PAREN_VALUE = Pattern.compile("\\(([^)]*)\\)");
  private static final Pattern JDK8_UPTIME = Pattern.compile("([0-9]+(?:\\.[0-9]+)?):\\s+\\[");
  private static final Pattern JDK8_WALL_CLOCK =
      Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\S+):\\s+[0-9]+(?:\\.[0-9]+)?:\\s+\\[");
  private static final Pattern XML_ATTRIBUTE = Pattern.compile("([A-Za-z_][A-Za-z0-9_-]*)=\"([^\"]*)\"");
  private static final DateTimeFormatter JDK_UNIFIED_OFFSET =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

  @Override
  public boolean canParse(Path path, ArtifactType hint) {
    if (hint != null) {
      return hint == ArtifactType.GC_LOG;
    }
    if (path == null || path.getFileName() == null) {
      return false;
    }
    String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return fileName.endsWith(".gc")
        || fileName.endsWith(".gclog")
        || fileName.endsWith(".gc.log")
        || fileName.endsWith(".log")
        || fileName.endsWith(".xml");
  }

  @Override
  public ParsedArtifact parse(Path path, ParseContext ctx) throws ParseException {
    if (!Files.isRegularFile(path)) {
      throw new ParseException("GC log file does not exist or is not a regular file: " + path);
    }

    Path normalized = path.toAbsolutePath().normalize();
    try {
      ctx.progressMonitor().beginTask("Parse GC log", Files.size(normalized));
      String text = Files.readString(normalized);
      Instant fallbackCapturedAt = Files.getLastModifiedTime(normalized).toInstant();
      ParsedGcLog parsed = parseText(text, fallbackCapturedAt);
      GcLogData data = new GcLogData(
          parsed.events(),
          parsed.format(),
          mapOf(
              "parser", "helix-gc-log",
              "event.count", parsed.events().size(),
              "format", parsed.format().name()));
      Artifact artifact = new Artifact(
          artifactId(normalized),
          ArtifactType.GC_LOG,
          parsed.events().isEmpty() ? fallbackCapturedAt : parsed.events().getFirst().timestamp(),
          Map.of(
              "filename", normalized.getFileName().toString(),
              "size.bytes", Long.toString(Files.size(normalized)),
              "parser", "helix-gc-log",
              "gc.format", parsed.format().name()),
          parsed.format() == GcLogFormat.OPENJ9_VERBOSE_GC ? JvmFlavour.OPENJ9 : JvmFlavour.HOTSPOT,
          normalized);
      ctx.progressMonitor().complete();
      return new ParsedArtifact(
          artifact,
          timelineEvents(artifact.id(), parsed.events()),
          Map.of(GC_DATA_KEY, data),
          parsed.warnings());
    } catch (IOException e) {
      throw new ParseException("Failed to read GC log file: " + normalized, e);
    } finally {
      ctx.progressMonitor().complete();
    }
  }

  @Override
  public ArtifactType artifactType() {
    return ArtifactType.GC_LOG;
  }

  static ParsedGcLog parseText(String text, Instant fallbackCapturedAt) {
    String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    if (normalized.isBlank()) {
      return new ParsedGcLog(List.of(), GcLogFormat.UNKNOWN, List.of("GC log is empty."));
    }
    if (normalized.contains("<verbosegc") || normalized.contains("<gc ")) {
      return parseOpenJ9(normalized, fallbackCapturedAt);
    }
    if (looksUnified(normalized)) {
      ParsedGcLog parsed = parseUnified(normalized, fallbackCapturedAt);
      if (!parsed.events().isEmpty()) {
        return parsed;
      }
    }
    return parseJdk8(normalized, fallbackCapturedAt);
  }

  private static ParsedGcLog parseUnified(String text, Instant fallbackCapturedAt) {
    Map<String, MutableEvent> byId = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    GcCollector detectedCollector = GcCollector.UNKNOWN;

    for (String line : text.split("\n", -1)) {
      if (line.contains("Using ")) {
        detectedCollector = inferCollector(line, detectedCollector);
      }
      UnifiedLine unified = unifiedLine(line, fallbackCapturedAt);
      if (unified == null) {
        if (line.contains("GC(")) {
          warnings.add("Ignored unrecognized unified GC line: " + line.trim());
        }
        continue;
      }
      Matcher id = GC_ID.matcher(unified.message());
      if (!id.matches()) {
        continue;
      }
      MutableEvent event = byId.computeIfAbsent(
          id.group(1),
          key -> new MutableEvent("gc-" + key, unified.timestamp(), unified.uptime(), GcLogFormat.UNIFIED));
      event.timestamp = earlier(event.timestamp, unified.timestamp());
      event.uptime = event.uptime.isZero() ? unified.uptime() : event.uptime;
      event.attributes.put("tags", unified.tags());
      event.collector = event.collector == GcCollector.UNKNOWN ? detectedCollector : event.collector;

      String message = id.group(2).trim();
      event.collector = inferCollector(message, event.collector);
      event.fullGc = event.fullGc || containsIgnoreCase(message, "Full");
      event.concurrent = event.concurrent || containsIgnoreCase(message, "Concurrent");
      parseNamedSpaces(message, event);

      if (unified.tags().contains("gc,start")) {
        event.name = message;
        event.cause = extractCause(message);
        continue;
      }

      Optional<Duration> duration = parseDurationAtEnd(message);
      if (duration.isPresent()) {
        event.duration = duration.get();
        event.name = event.name.isBlank() ? stripTrailingDuration(message) : event.name;
        event.cause = event.cause.isBlank() ? extractCause(message) : event.cause;
        event.systemGc = event.systemGc || containsIgnoreCase(message, "System.gc");
        parseHeapTransition(message, event);
      }
    }

    List<GcEvent> events = byId.values().stream()
        .filter(event -> !event.name.isBlank() || !event.duration.isZero() || event.heapAfterMb != null)
        .map(event -> {
          if (event.duration.isZero()) {
            warnings.add("GC event " + event.id + " did not include a pause duration.");
          }
          event.humongousAllocation = containsIgnoreCase(event.cause, "Humongous");
          return event.toEvent();
        })
        .toList();
    if (events.isEmpty()) {
      warnings.add("No JDK unified GC events were recognized.");
    }
    return new ParsedGcLog(events, GcLogFormat.UNIFIED, warnings);
  }

  private static ParsedGcLog parseJdk8(String text, Instant fallbackCapturedAt) {
    List<GcEvent> events = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    int index = 0;

    for (String line : text.split("\n", -1)) {
      String trimmed = line.trim();
      if (!trimmed.contains("[GC") && !trimmed.contains("[Full GC")) {
        continue;
      }

      Optional<Duration> duration = parseJdk8Duration(trimmed);
      if (duration.isEmpty()) {
        warnings.add("Incomplete GC event without duration: " + trimmed);
      }

      Duration uptime = leadingUptime(trimmed).orElse(Duration.ZERO);
      Instant timestamp = leadingWallClock(trimmed).orElse(fallbackCapturedAt.plus(uptime));
      MutableEvent event = new MutableEvent("gc-" + index++, timestamp, uptime, GcLogFormat.JDK8);
      event.duration = duration.orElse(Duration.ZERO);
      event.name = trimmed;
      event.collector = inferCollector(trimmed, GcCollector.UNKNOWN);
      event.fullGc = containsIgnoreCase(trimmed, "Full GC");
      event.concurrent = containsIgnoreCase(trimmed, "concurrent");
      event.cause = extractCause(trimmed);
      event.systemGc = containsIgnoreCase(trimmed, "System.gc");
      event.humongousAllocation = containsIgnoreCase(trimmed, "Humongous");
      parseNamedSpaces(trimmed, event);
      parseHeapTransition(trimmed, event);
      events.add(event.toEvent());
    }

    if (events.isEmpty()) {
      warnings.add("No JDK 8 GC events were recognized.");
    }
    return new ParsedGcLog(events, GcLogFormat.JDK8, warnings);
  }

  private static ParsedGcLog parseOpenJ9(String text, Instant fallbackCapturedAt) {
    List<GcEvent> events = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    MutableEvent current = null;
    Instant pendingTimestamp = fallbackCapturedAt;
    Duration pendingUptime = Duration.ZERO;
    String pendingCause = "";
    int syntheticId = 0;

    for (String line : text.split("\n", -1)) {
      String trimmed = line.trim();
      if (trimmed.startsWith("<af ")) {
        Map<String, String> attributes = xmlAttributes(trimmed);
        pendingTimestamp = xmlTimestamp(attributes).orElse(pendingTimestamp);
        pendingUptime = millisAttribute(attributes, "intervalms").orElse(pendingUptime);
        pendingCause = attributes.getOrDefault("type", "");
        continue;
      }

      if (trimmed.startsWith("<gc ")) {
        Map<String, String> attributes = xmlAttributes(trimmed);
        String id = attributes.getOrDefault("id", Integer.toString(syntheticId++));
        current = new MutableEvent("gc-" + id, xmlTimestamp(attributes).orElse(pendingTimestamp), pendingUptime, GcLogFormat.OPENJ9_VERBOSE_GC);
        String type = attributes.getOrDefault("type", "");
        current.name = "OpenJ9 " + (type.isBlank() ? "gc" : type + " gc");
        current.collector = GcCollector.OPENJ9;
        current.fullGc = "global".equalsIgnoreCase(type);
        current.cause = pendingCause.isBlank() ? type : pendingCause;
        current.duration = millisAttribute(attributes, "totalms")
            .or(() -> millisAttribute(attributes, "durationms"))
            .orElse(Duration.ZERO);
        current.attributes.put("openj9.gc.type", type);
        continue;
      }

      if (current == null) {
        continue;
      }

      if (trimmed.startsWith("<time ")) {
        Map<String, String> attributes = xmlAttributes(trimmed);
        current.duration = millisAttribute(attributes, "totalms")
            .or(() -> millisAttribute(attributes, "durationms"))
            .orElse(current.duration);
      } else if (trimmed.startsWith("<mem-info ")) {
        Map<String, String> attributes = xmlAttributes(trimmed);
        current.heapBeforeMb = bytesAttribute(attributes, "beforebytes").orElse(current.heapBeforeMb);
        current.heapAfterMb = bytesAttribute(attributes, "afterbytes").orElse(current.heapAfterMb);
        current.heapCommittedMb = bytesAttribute(attributes, "totalbytes")
            .or(() -> bytesAttribute(attributes, "total"))
            .orElse(current.heapCommittedMb);
      } else if (trimmed.startsWith("<mem ")) {
        parseOpenJ9Mem(trimmed, current);
      }

      if (trimmed.contains("</gc>")) {
        finishOpenJ9Event(current);
        if (current.duration.isZero()) {
          warnings.add("OpenJ9 GC event " + current.id + " did not include totalms.");
        }
        events.add(current.toEvent());
        current = null;
      }
    }

    if (current != null) {
      finishOpenJ9Event(current);
      warnings.add("OpenJ9 GC event " + current.id + " was truncated before </gc>.");
      events.add(current.toEvent());
    }
    if (events.isEmpty()) {
      warnings.add("No OpenJ9 verbose GC events were recognized.");
    }
    return new ParsedGcLog(events, GcLogFormat.OPENJ9_VERBOSE_GC, warnings);
  }

  private static void finishOpenJ9Event(MutableEvent event) {
    if (event.heapBeforeMb == null && event.nurseryBeforeMb != null && event.tenureBeforeMb != null) {
      event.heapBeforeMb = event.nurseryBeforeMb + event.tenureBeforeMb;
    }
    if (event.heapAfterMb == null && event.nurseryAfterMb != null && event.tenureAfterMb != null) {
      event.heapAfterMb = event.nurseryAfterMb + event.tenureAfterMb;
    }
  }

  private static void parseOpenJ9Mem(String line, MutableEvent event) {
    Map<String, String> attributes = xmlAttributes(line);
    String type = attributes.getOrDefault("type", "").toLowerCase(Locale.ROOT);
    Optional<Double> total = bytesAttribute(attributes, "totalbytes")
        .or(() -> bytesAttribute(attributes, "total"));
    Optional<Double> before = bytesAttribute(attributes, "beforebytes")
        .or(() -> usedBytes(attributes, "freebytesbefore", total));
    Optional<Double> after = bytesAttribute(attributes, "afterbytes")
        .or(() -> usedBytes(attributes, "freebytesafter", total))
        .or(() -> usedBytes(attributes, "freebytes", total));
    if ("nursery".equals(type)) {
      event.nurseryBeforeMb = before.orElse(event.nurseryBeforeMb);
      event.nurseryAfterMb = after.orElse(event.nurseryAfterMb);
      event.edenBeforeMb = event.nurseryBeforeMb;
      event.edenAfterMb = event.nurseryAfterMb;
    } else if ("tenure".equals(type) || "tenured".equals(type)) {
      event.tenureBeforeMb = before.orElse(event.tenureBeforeMb);
      event.tenureAfterMb = after.orElse(event.tenureAfterMb);
      event.oldBeforeMb = event.tenureBeforeMb;
      event.oldAfterMb = event.tenureAfterMb;
    }
  }

  private static List<TimelineEvent> timelineEvents(String artifactId, List<GcEvent> events) {
    List<TimelineEvent> timeline = new ArrayList<>();
    for (GcEvent event : events) {
      TimelineEventType type;
      if (event.systemGc()) {
        type = TimelineEventType.SYSTEM_GC;
      } else if (event.humongousAllocation()) {
        type = TimelineEventType.HUMONGOUS_ALLOCATION;
      } else if (event.fullGc()) {
        type = TimelineEventType.FULL_GC;
      } else {
        type = TimelineEventType.GC_PAUSE;
      }
      timeline.add(new TimelineEvent(
          event.timestamp(),
          artifactId,
          type,
          event.duration(),
          mapOf(
              "gc.id", event.id(),
              "collector", event.collector().name(),
              "cause", event.cause(),
              "heap.before.mb", event.heapBeforeMb() == null ? "" : event.heapBeforeMb(),
              "heap.after.mb", event.heapAfterMb() == null ? "" : event.heapAfterMb())));
    }
    return timeline;
  }

  private static boolean looksUnified(String text) {
    for (String line : text.split("\n", -1)) {
      if (line.startsWith("[") && line.contains("][") && line.contains("GC(")) {
        return true;
      }
    }
    return false;
  }

  private static UnifiedLine unifiedLine(String line, Instant fallbackCapturedAt) {
    if (line == null || !line.startsWith("[")) {
      return null;
    }
    List<String> tokens = new ArrayList<>();
    int index = 0;
    while (index < line.length() && line.charAt(index) == '[') {
      int close = line.indexOf(']', index + 1);
      if (close < 0) {
        return null;
      }
      tokens.add(line.substring(index + 1, close));
      index = close + 1;
    }
    String message = line.substring(index).trim();
    if (tokens.stream().noneMatch(token -> token.contains("gc")) || !message.contains("GC(")) {
      return null;
    }

    Duration uptime = Duration.ZERO;
    Instant timestamp = null;
    String tags = "";
    for (String token : tokens) {
      if (token.matches("[0-9]+(?:\\.[0-9]+)?s")) {
        uptime = seconds(Double.parseDouble(token.substring(0, token.length() - 1)));
      } else if (token.contains("gc")) {
        tags = token;
      } else if (timestamp == null) {
        timestamp = parseInstant(token).orElse(null);
      }
    }
    if (timestamp == null) {
      timestamp = fallbackCapturedAt.plus(uptime);
    }
    return new UnifiedLine(timestamp, uptime, tags, message);
  }

  private static void parseNamedSpaces(String text, MutableEvent event) {
    Matcher named = NAMED_SPACE.matcher(text);
    while (named.find()) {
      Double before = memoryToMb(named.group(2)).orElse(null);
      Double after = memoryToMb(named.group(3)).orElse(null);
      Double committed = memoryToMb(named.group(4)).orElse(null);
      switch (named.group(1)) {
        case "Eden" -> {
          event.edenBeforeMb = before;
          event.edenAfterMb = after;
        }
        case "Old" -> {
          event.oldBeforeMb = before;
          event.oldAfterMb = after;
        }
        case "Heap" -> {
          event.heapBeforeMb = before;
          event.heapAfterMb = after;
          event.heapCommittedMb = committed;
        }
        default -> {
        }
      }
    }
  }

  private static void parseHeapTransition(String text, MutableEvent event) {
    Matcher heap = MEMORY_TRANSITION.matcher(text);
    Double before = null;
    Double after = null;
    Double committed = null;
    while (heap.find()) {
      before = memoryToMb(heap.group(1)).orElse(null);
      after = memoryToMb(heap.group(2)).orElse(null);
      committed = memoryToMb(heap.group(3)).orElse(null);
    }
    if (before != null && after != null) {
      event.heapBeforeMb = event.heapBeforeMb == null ? before : event.heapBeforeMb;
      event.heapAfterMb = event.heapAfterMb == null ? after : event.heapAfterMb;
      event.heapCommittedMb = event.heapCommittedMb == null ? committed : event.heapCommittedMb;
    }
  }

  private static Optional<Duration> parseDurationAtEnd(String text) {
    Matcher duration = DURATION_AT_END.matcher(text);
    if (!duration.find()) {
      return Optional.empty();
    }
    double value = Double.parseDouble(duration.group(1));
    return switch (duration.group(2)) {
      case "ms" -> Optional.of(millis(value));
      default -> Optional.of(seconds(value));
    };
  }

  private static Optional<Duration> parseJdk8Duration(String text) {
    Matcher matcher = JDK8_DURATION.matcher(text);
    Duration result = null;
    while (matcher.find()) {
      result = seconds(Double.parseDouble(matcher.group(1)));
    }
    return Optional.ofNullable(result);
  }

  private static String stripTrailingDuration(String text) {
    return DURATION_AT_END.matcher(text).replaceFirst("").trim();
  }

  private static Optional<Duration> leadingUptime(String line) {
    Matcher matcher = JDK8_UPTIME.matcher(line);
    Duration result = null;
    while (matcher.find()) {
      result = seconds(Double.parseDouble(matcher.group(1)));
    }
    return Optional.ofNullable(result);
  }

  private static Optional<Instant> leadingWallClock(String line) {
    Matcher matcher = JDK8_WALL_CLOCK.matcher(line);
    if (!matcher.find()) {
      return Optional.empty();
    }
    return parseInstant(matcher.group(1));
  }

  private static String extractCause(String text) {
    List<String> values = new ArrayList<>();
    Matcher matcher = PAREN_VALUE.matcher(text);
    while (matcher.find()) {
      String value = matcher.group(1).trim();
      if (!value.isBlank()) {
        values.add(value);
      }
    }
    return values.stream()
        .filter(value -> containsIgnoreCase(value, "System.gc")
            || containsIgnoreCase(value, "Allocation Failure")
            || containsIgnoreCase(value, "Metadata")
            || containsIgnoreCase(value, "Humongous")
            || containsIgnoreCase(value, "G1")
            || containsIgnoreCase(value, "Warmup"))
        .reduce((first, second) -> second)
        .orElseGet(() -> values.stream()
            .filter(value -> memoryToMb(value).isEmpty())
            .findFirst()
            .orElse(""));
  }

  private static GcCollector inferCollector(String text, GcCollector fallback) {
    if (containsIgnoreCase(text, "OpenJ9")) {
      return GcCollector.OPENJ9;
    }
    if (containsIgnoreCase(text, "ZGC") || containsIgnoreCase(text, "Z Garbage")) {
      return GcCollector.ZGC;
    }
    if (containsIgnoreCase(text, "Shenandoah")) {
      return GcCollector.SHENANDOAH;
    }
    if (containsIgnoreCase(text, "G1") || containsIgnoreCase(text, "Humongous")) {
      return GcCollector.G1;
    }
    if (containsIgnoreCase(text, "CMS") || containsIgnoreCase(text, "concurrent mark-sweep")) {
      return GcCollector.CMS;
    }
    if (containsIgnoreCase(text, "PSYoungGen") || containsIgnoreCase(text, "ParOldGen") || containsIgnoreCase(text, "Parallel")) {
      return GcCollector.PARALLEL;
    }
    if (containsIgnoreCase(text, "DefNew") || containsIgnoreCase(text, "Serial")) {
      return GcCollector.SERIAL;
    }
    return fallback;
  }

  private static Optional<Double> memoryToMb(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    Matcher matcher = MEMORY_VALUE.matcher(value.trim().toUpperCase(Locale.ROOT));
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String normalized = matcher.group(1);
    int index = 0;
    while (index < normalized.length()
        && (Character.isDigit(normalized.charAt(index)) || normalized.charAt(index) == '.')) {
      index++;
    }
    double numeric = Double.parseDouble(normalized.substring(0, index));
    String unit = normalized.substring(index);
    return Optional.of(switch (unit) {
      case "B" -> numeric / (1024.0 * 1024.0);
      case "K", "KB" -> numeric / 1024.0;
      case "M", "MB" -> numeric;
      case "G", "GB" -> numeric * 1024.0;
      case "T", "TB" -> numeric * 1024.0 * 1024.0;
      default -> numeric;
    });
  }

  private static Optional<Instant> parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(value));
    } catch (DateTimeParseException ignored) {
      try {
        return Optional.of(OffsetDateTime.parse(value).toInstant());
      } catch (DateTimeParseException ignoredAgain) {
        try {
          return Optional.of(OffsetDateTime.parse(value, JDK_UNIFIED_OFFSET).toInstant());
        } catch (DateTimeParseException ignoredFinal) {
          return Optional.empty();
        }
      }
    }
  }

  private static Map<String, String> xmlAttributes(String line) {
    Map<String, String> attributes = new LinkedHashMap<>();
    Matcher matcher = XML_ATTRIBUTE.matcher(line);
    while (matcher.find()) {
      attributes.put(matcher.group(1), matcher.group(2));
    }
    return attributes;
  }

  private static Optional<Instant> xmlTimestamp(Map<String, String> attributes) {
    return parseInstant(attributes.get("timestamp"));
  }

  private static Optional<Duration> millisAttribute(Map<String, String> attributes, String name) {
    String value = attributes.get(name);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(millis(Double.parseDouble(value)));
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private static Optional<Double> bytesAttribute(Map<String, String> attributes, String name) {
    String value = attributes.get(name);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Double.parseDouble(value) / (1024.0 * 1024.0));
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private static Optional<Double> usedBytes(
      Map<String, String> attributes,
      String freeAttribute,
      Optional<Double> totalMb) {
    Optional<Double> freeMb = bytesAttribute(attributes, freeAttribute);
    if (freeMb.isEmpty() || totalMb.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(Math.max(0.0, totalMb.get() - freeMb.get()));
  }

  private static Duration millis(double value) {
    return Duration.ofNanos(Math.round(value * 1_000_000.0));
  }

  private static Duration seconds(double value) {
    return Duration.ofNanos(Math.round(value * 1_000_000_000.0));
  }

  private static Instant earlier(Instant left, Instant right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.isBefore(right) ? left : right;
  }

  private static boolean containsIgnoreCase(String text, String pattern) {
    return text != null && text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
  }

  private static String artifactId(Path path) throws IOException {
    String fileName = path.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "-");
    long size = Files.size(path);
    Instant modified = Files.getLastModifiedTime(path).toInstant();
    return "gclog-" + fileName + "-" + Long.toHexString((fileName + size + modified).hashCode());
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

  record ParsedGcLog(List<GcEvent> events, GcLogFormat format, List<String> warnings) {
    ParsedGcLog {
      events = events == null ? List.of() : List.copyOf(events);
      format = format == null ? GcLogFormat.UNKNOWN : format;
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }

  private record UnifiedLine(Instant timestamp, Duration uptime, String tags, String message) {}

  private static final class MutableEvent {
    private final String id;
    private Instant timestamp;
    private Duration uptime;
    private Duration duration = Duration.ZERO;
    private String name = "";
    private GcCollector collector = GcCollector.UNKNOWN;
    private String cause = "";
    private boolean fullGc;
    private boolean concurrent;
    private boolean systemGc;
    private boolean humongousAllocation;
    private Double heapBeforeMb;
    private Double heapAfterMb;
    private Double heapCommittedMb;
    private Double edenBeforeMb;
    private Double edenAfterMb;
    private Double oldBeforeMb;
    private Double oldAfterMb;
    private Double nurseryBeforeMb;
    private Double nurseryAfterMb;
    private Double tenureBeforeMb;
    private Double tenureAfterMb;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    private MutableEvent(String id, Instant timestamp, Duration uptime, GcLogFormat format) {
      this.id = id;
      this.timestamp = timestamp;
      this.uptime = uptime == null ? Duration.ZERO : uptime;
      this.attributes.put("format", format);
    }

    private GcEvent toEvent() {
      return new GcEvent(
          id,
          timestamp,
          uptime,
          duration,
          name,
          collector,
          cause,
          fullGc,
          concurrent,
          systemGc,
          humongousAllocation,
          heapBeforeMb,
          heapAfterMb,
          heapCommittedMb,
          edenBeforeMb,
          edenAfterMb,
          oldBeforeMb,
          oldAfterMb,
          nurseryBeforeMb,
          nurseryAfterMb,
          tenureBeforeMb,
          tenureAfterMb,
          attributes);
    }
  }
}
