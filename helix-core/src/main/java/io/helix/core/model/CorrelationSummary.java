package io.helix.core.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CorrelationSummary(
    String narrative,
    List<CorrelatedEvent> correlatedEvents,
    Map<String, Object> metrics
) {
  public CorrelationSummary {
    narrative = Objects.requireNonNullElse(narrative, "");
    correlatedEvents = correlatedEvents == null ? List.of() : List.copyOf(correlatedEvents);
    metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
  }

  public static CorrelationSummary empty() {
    return new CorrelationSummary("", List.of(), Map.of());
  }
}
