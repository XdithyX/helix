package io.helix.core.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record TimelineEvent(
    Instant timestamp,
    String artifactId,
    TimelineEventType type,
    Duration duration,
    Map<String, Object> attributes
) {
  public TimelineEvent {
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
    if (artifactId == null || artifactId.isBlank()) {
      throw new IllegalArgumentException("artifactId must not be blank");
    }
    type = Objects.requireNonNull(type, "type");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
