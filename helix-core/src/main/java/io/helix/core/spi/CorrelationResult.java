package io.helix.core.spi;

import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.Finding;
import io.helix.core.model.TimelineEvent;
import java.util.List;
import java.util.Objects;

public record CorrelationResult(
    CorrelationSummary summary,
    List<Finding> findings,
    List<TimelineEvent> timeline
) {
  public CorrelationResult {
    summary = summary == null ? CorrelationSummary.empty() : summary;
    findings = findings == null ? List.of() : List.copyOf(findings);
    timeline = timeline == null ? List.of() : List.copyOf(timeline);
    Objects.requireNonNull(summary, "summary");
  }
}
