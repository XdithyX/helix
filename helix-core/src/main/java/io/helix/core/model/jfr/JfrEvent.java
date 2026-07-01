package io.helix.core.model.jfr;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record JfrEvent(
    String eventType,
    Instant startTime,
    Duration duration,
    String threadName,
    List<JfrFrame> stackTrace,
    long bytes,
    String className,
    String methodName,
    String lockName,
    String ioTarget,
    String reason,
    Map<String, Object> attributes
) {
  public JfrEvent {
    eventType = eventType == null || eventType.isBlank() ? "<unknown-jfr-event>" : eventType;
    startTime = startTime == null ? Instant.EPOCH : startTime;
    duration = duration == null ? Duration.ZERO : duration;
    threadName = threadName == null ? "" : threadName;
    stackTrace = stackTrace == null ? List.of() : List.copyOf(stackTrace);
    className = className == null ? "" : className;
    methodName = methodName == null ? "" : methodName;
    lockName = lockName == null ? "" : lockName;
    ioTarget = ioTarget == null ? "" : ioTarget;
    reason = reason == null ? "" : reason;
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  public double durationMillis() {
    return duration.toNanos() / 1_000_000.0;
  }

  public String topFrame() {
    return stackTrace.isEmpty() ? "" : stackTrace.getFirst().displayName();
  }
}
