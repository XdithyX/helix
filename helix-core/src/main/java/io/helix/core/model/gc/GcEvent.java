package io.helix.core.model.gc;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public record GcEvent(
    String id,
    Instant timestamp,
    Duration uptime,
    Duration duration,
    String name,
    GcCollector collector,
    String cause,
    boolean fullGc,
    boolean concurrent,
    boolean systemGc,
    boolean humongousAllocation,
    Double heapBeforeMb,
    Double heapAfterMb,
    Double heapCommittedMb,
    Double edenBeforeMb,
    Double edenAfterMb,
    Double oldBeforeMb,
    Double oldAfterMb,
    Double nurseryBeforeMb,
    Double nurseryAfterMb,
    Double tenureBeforeMb,
    Double tenureAfterMb,
    Map<String, Object> attributes
) {
  public GcEvent {
    id = id == null || id.isBlank() ? "<unknown-gc-event>" : id;
    timestamp = timestamp == null ? Instant.EPOCH : timestamp;
    uptime = uptime == null ? Duration.ZERO : uptime;
    duration = duration == null ? Duration.ZERO : duration;
    name = name == null ? "" : name;
    collector = collector == null ? GcCollector.UNKNOWN : collector;
    cause = cause == null ? "" : cause;
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  public boolean pause() {
    return !concurrent && !duration.isNegative() && !duration.isZero();
  }

  public double durationMillis() {
    return duration.toNanos() / 1_000_000.0;
  }

  public Double heapReclaimedMb() {
    if (heapBeforeMb == null || heapAfterMb == null) {
      return null;
    }
    return Math.max(0.0, heapBeforeMb - heapAfterMb);
  }
}
