package io.helix.agent.core;

import io.helix.core.model.TimelineEvent;
import java.util.List;

public record CorrelationLink(
    String ruleName,
    TimelineEvent primary,
    TimelineEvent related,
    String explanation,
    double confidence
) {
  public CorrelationLink {
    if (ruleName == null || ruleName.isBlank()) {
      throw new IllegalArgumentException("ruleName must not be blank");
    }
    if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
    }
    explanation = explanation == null ? "" : explanation;
  }

  public List<TimelineEvent> evidence() {
    if (primary == null && related == null) {
      return List.of();
    }
    if (primary == null) {
      return List.of(related);
    }
    if (related == null || primary.equals(related)) {
      return List.of(primary);
    }
    return List.of(primary, related);
  }
}
