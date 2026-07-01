package io.helix.core.model.thread;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ThreadDumpSnapshot(
    Instant capturedAt,
    ThreadDumpFormat format,
    List<ThreadSample> threads,
    Map<String, Object> metadata
) {
  public ThreadDumpSnapshot {
    capturedAt = capturedAt == null ? Instant.EPOCH : capturedAt;
    format = format == null ? ThreadDumpFormat.UNKNOWN : format;
    threads = threads == null ? List.of() : List.copyOf(threads);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
