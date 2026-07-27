package io.helix.agent.core.investigation;

import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.ParsedArtifact;
import java.util.List;
import java.util.Objects;

public record InvestigationContext(
    String question,
    DiagnosticReport report,
    List<ParsedArtifact> parsedArtifacts
) {
  public InvestigationContext {
    question = question == null ? "" : question.trim();
    report = Objects.requireNonNull(report, "report");
    parsedArtifacts = parsedArtifacts == null ? List.of() : List.copyOf(parsedArtifacts);
  }
}
