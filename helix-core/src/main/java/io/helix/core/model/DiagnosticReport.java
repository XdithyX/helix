package io.helix.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record DiagnosticReport(
    String reportId,
    Instant generatedAt,
    List<Artifact> artifacts,
    List<Finding> findings,
    List<TimelineEvent> timeline,
    CorrelationSummary correlationSummary,
    JvmProfile jvmProfile,
    String llmNarrative
) {
  public DiagnosticReport {
    if (reportId == null || reportId.isBlank()) {
      throw new IllegalArgumentException("reportId must not be blank");
    }
    generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
    artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    findings = findings == null ? List.of() : List.copyOf(findings);
    timeline = timeline == null ? List.of() : List.copyOf(timeline);
    correlationSummary =
        correlationSummary == null ? CorrelationSummary.empty() : correlationSummary;
    jvmProfile = jvmProfile == null ? JvmProfile.unknown() : jvmProfile;
  }
}
