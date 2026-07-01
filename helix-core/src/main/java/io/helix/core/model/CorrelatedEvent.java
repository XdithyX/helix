package io.helix.core.model;

import java.util.Objects;

public record CorrelatedEvent(
    String ruleName,
    TimelineEvent primaryEvent,
    TimelineEvent relatedEvent,
    double confidence,
    String explanation
) {
  public CorrelatedEvent {
    if (ruleName == null || ruleName.isBlank()) {
      throw new IllegalArgumentException("ruleName must not be blank");
    }
    primaryEvent = Objects.requireNonNull(primaryEvent, "primaryEvent");
    relatedEvent = Objects.requireNonNull(relatedEvent, "relatedEvent");
    if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
    }
    explanation = Objects.requireNonNullElse(explanation, "");
  }
}
