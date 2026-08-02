package io.helix.parser.jfr;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.TimelineEvent;
import io.helix.core.model.TimelineEventType;
import io.helix.core.model.jfr.JfrEvent;
import io.helix.core.model.jfr.JfrFrame;
import io.helix.core.model.jfr.JfrRecordingData;
import io.helix.core.spi.ArtifactParser;
import io.helix.core.spi.ParseContext;
import io.helix.core.spi.ParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

public final class JfrArtifactParser implements ArtifactParser {
  public static final String JFR_DATA_KEY = "jfrRecording";

  private static final Set<String> SUPPORTED_EVENTS = Set.of(
      "jdk.GCPauseL1",
      "jdk.G1GarbageCollection",
      "jdk.GCHeapSummary",
      "jdk.ExecutionSample",
      "jdk.ObjectAllocationInNewTLAB",
      "jdk.ObjectAllocationOutsideTLAB",
      "jdk.ObjectAllocationSample",
      "jdk.JavaMonitorEnter",
      "jdk.JavaMonitorWait",
      "jdk.FileRead",
      "jdk.FileWrite",
      "jdk.SocketRead",
      "jdk.SocketWrite",
      "jdk.Deoptimization",
      "jdk.CompilerInliningFailed",
      "jdk.VirtualThreadPinned",
      "jdk.NativeLibraryLoad");

  @Override
  public boolean canParse(Path path, ArtifactType hint) {
    if (hint != null) {
      return hint == ArtifactType.JFR;
    }
    return path != null
        && path.getFileName() != null
        && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jfr");
  }

  @Override
  public ParsedArtifact parse(Path path, ParseContext ctx) throws ParseException {
    if (!Files.isRegularFile(path)) {
      throw new ParseException("JFR file does not exist or is not a regular file: " + path);
    }

    Path normalized = path.toAbsolutePath().normalize();
    try {
      ctx.progressMonitor().beginTask("Parse JFR recording", Files.size(normalized));
      List<JfrEvent> events = new ArrayList<>();
      List<String> warnings = new ArrayList<>();
      Map<String, Long> eventCounts = new LinkedHashMap<>();

      try (RecordingFile recording = new RecordingFile(normalized)) {
        while (recording.hasMoreEvents()) {
          RecordedEvent event = recording.readEvent();
          String eventType = event.getEventType().getName();
          if (!SUPPORTED_EVENTS.contains(eventType)) {
            continue;
          }
          try {
            JfrEvent parsed = parseEvent(event);
            events.add(parsed);
            eventCounts.merge(eventType, 1L, Long::sum);
          } catch (RuntimeException e) {
            warnings.add("Ignored malformed JFR event " + eventType + ": " + e.getMessage());
          }
        }
      }

      Instant fallbackCapturedAt = Files.getLastModifiedTime(normalized).toInstant();
      JfrRecordingData data = new JfrRecordingData(
          events,
          mapOf(
              "parser", "helix-jfr",
              "event.count", events.size(),
              "event.counts", eventCounts));
      Artifact artifact = new Artifact(
          artifactId(normalized),
          ArtifactType.JFR,
          events.isEmpty() ? fallbackCapturedAt : events.getFirst().startTime(),
          Map.of(
              "filename", normalized.getFileName().toString(),
              "size.bytes", Long.toString(Files.size(normalized)),
              "parser", "helix-jfr"),
          JvmFlavour.HOTSPOT,
          normalized);
      if (events.isEmpty()) {
        warnings.add("No supported JFR events were recognized.");
      }
      ctx.progressMonitor().complete();
      return new ParsedArtifact(
          artifact,
          timelineEvents(artifact.id(), events),
          Map.of(JFR_DATA_KEY, data),
          warnings);
    } catch (IOException e) {
      throw new ParseException("Failed to read JFR file: " + normalized, e);
    } finally {
      ctx.progressMonitor().complete();
    }
  }

  @Override
  public ArtifactType artifactType() {
    return ArtifactType.JFR;
  }

  private static JfrEvent parseEvent(RecordedEvent event) {
    String eventType = event.getEventType().getName();
    return new JfrEvent(
        eventType,
        event.getStartTime(),
        event.getDuration() == null ? Duration.ZERO : event.getDuration(),
        threadName(event),
        stackTrace(event),
        bytes(event),
        className(event),
        methodName(event),
        lockName(event),
        ioTarget(event),
        reason(event),
        attributes(event));
  }

  private static List<TimelineEvent> timelineEvents(String artifactId, List<JfrEvent> events) {
    List<TimelineEvent> timeline = new ArrayList<>();
    for (JfrEvent event : events) {
      TimelineEventType type = timelineType(event.eventType());
      if (type == null) {
        continue;
      }
      timeline.add(new TimelineEvent(
          event.startTime(),
          artifactId,
          type,
          event.duration(),
          mapOf(
              "jfr.event", event.eventType(),
              "thread", event.threadName(),
              "top.frame", event.topFrame(),
              "bytes", event.bytes(),
              "class", event.className(),
              "method", event.methodName(),
              "lock", event.lockName(),
              "io.target", event.ioTarget(),
              "reason", event.reason())));
    }
    return timeline;
  }

  private static TimelineEventType timelineType(String eventType) {
    return switch (eventType) {
      case "jdk.GCPauseL1", "jdk.G1GarbageCollection" -> TimelineEventType.GC_PAUSE;
      case "jdk.ExecutionSample" -> TimelineEventType.CPU_SPIKE;
      case "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB", "jdk.ObjectAllocationSample" ->
          TimelineEventType.ALLOCATION_PRESSURE;
      case "jdk.JavaMonitorEnter" -> TimelineEventType.THREAD_BLOCKED;
      case "jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite" -> TimelineEventType.BLOCKING_IO;
      case "jdk.Deoptimization" -> TimelineEventType.DEOPT;
      case "jdk.VirtualThreadPinned" -> TimelineEventType.VIRTUAL_THREAD_PINNED;
      default -> null;
    };
  }

  private static String threadName(RecordedEvent event) {
    Optional<Object> value = firstValue(event, "eventThread", "thread");
    if (value.isPresent() && value.get() instanceof RecordedThread thread) {
      String javaName = thread.getJavaName();
      if (javaName != null && !javaName.isBlank()) {
        return javaName;
      }
      return "tid-" + thread.getJavaThreadId();
    }
    return "";
  }

  private static List<JfrFrame> stackTrace(RecordedEvent event) {
    RecordedStackTrace stackTrace = event.getStackTrace();
    if (stackTrace == null) {
      return List.of();
    }
    List<JfrFrame> frames = new ArrayList<>();
    for (RecordedFrame frame : stackTrace.getFrames()) {
      RecordedMethod method = frame.getMethod();
      if (method == null) {
        continue;
      }
      String className = method.getType() == null ? "" : method.getType().getName();
      frames.add(new JfrFrame(className, method.getName(), frame.getLineNumber()));
    }
    return frames;
  }

  private static long bytes(RecordedEvent event) {
    return firstNumber(event, "allocationSize", "tlabSize", "weight", "bytesRead", "bytesWritten", "bytes")
        .map(Number::longValue)
        .orElse(0L);
  }

  private static String className(RecordedEvent event) {
    Optional<Object> value = firstValue(event, "objectClass", "allocationClass", "monitorClass", "sampledObject");
    if (value.isPresent()) {
      Object object = value.get();
      if (object instanceof RecordedClass recordedClass) {
        return recordedClass.getName();
      }
      return object.toString();
    }
    return "";
  }

  private static String methodName(RecordedEvent event) {
    Optional<Object> value = firstValue(event, "method", "compileId");
    if (value.isPresent()) {
      Object object = value.get();
      if (object instanceof RecordedMethod method) {
        String owner = method.getType() == null ? "" : method.getType().getName() + ".";
        return owner + method.getName();
      }
      return object.toString();
    }
    return "";
  }

  private static String lockName(RecordedEvent event) {
    Optional<Object> value = firstValue(event, "monitorClass", "address", "notifier");
    if (value.isPresent() && value.get() instanceof RecordedClass recordedClass) {
      return recordedClass.getName();
    }
    return value.map(Object::toString).orElse("");
  }

  private static String ioTarget(RecordedEvent event) {
    Optional<Object> path = firstValue(event, "path");
    if (path.isPresent()) {
      return path.get().toString();
    }
    Optional<Object> host = firstValue(event, "host", "address");
    Optional<Number> port = firstNumber(event, "port");
    if (host.isPresent()) {
      return host.get() + port.map(number -> ":" + number).orElse("");
    }
    return "";
  }

  private static String reason(RecordedEvent event) {
    return firstValue(event, "reason", "action", "failureMessage", "cause")
        .map(Object::toString)
        .orElse("");
  }

  private static Map<String, Object> attributes(RecordedEvent event) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    for (ValueDescriptor field : event.getFields()) {
      String name = field.getName();
      if (Set.of(
          "stackTrace",
          "eventThread",
          "thread",
          "objectClass",
          "allocationClass",
          "monitorClass",
          "method").contains(name)) {
        continue;
      }
      firstValue(event, name).ifPresent(value -> attributes.put(name, simpleValue(value)));
    }
    return attributes;
  }

  private static Optional<Number> firstNumber(RecordedEvent event, String... names) {
    return firstValue(event, names)
        .filter(Number.class::isInstance)
        .map(Number.class::cast);
  }

  private static Optional<Object> firstValue(RecordedEvent event, String... names) {
    for (String name : names) {
      if (!hasField(event, name)) {
        continue;
      }
      Object value = event.getValue(name);
      if (value != null) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  private static boolean hasField(RecordedObject object, String fieldName) {
    return object.getFields().stream().map(ValueDescriptor::getName).anyMatch(fieldName::equals);
  }

  private static Object simpleValue(Object value) {
    if (value instanceof RecordedClass recordedClass) {
      return recordedClass.getName();
    }
    if (value instanceof RecordedThread thread) {
      return thread.getJavaName();
    }
    if (value instanceof RecordedMethod method) {
      String owner = method.getType() == null ? "" : method.getType().getName() + ".";
      return owner + method.getName();
    }
    return value.toString();
  }

  private static String artifactId(Path path) throws IOException {
    String fileName = path.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "-");
    long size = Files.size(path);
    Instant modified = Files.getLastModifiedTime(path).toInstant();
    return "jfr-" + fileName + "-" + Long.toHexString((fileName + size + modified).hashCode());
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
}
