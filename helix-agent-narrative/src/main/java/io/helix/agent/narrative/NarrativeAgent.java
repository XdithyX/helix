package io.helix.agent.narrative;

import io.helix.agent.core.AgentContext;
import io.helix.agent.core.AgentResult;
import io.helix.agent.core.CorrelatedDiagnosis;
import io.helix.agent.core.NarrativeDiagnosticAgent;
import io.helix.agent.core.NarrativeResult;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingSeverity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import io.helix.core.model.FindingCategory;

public final class NarrativeAgent implements NarrativeDiagnosticAgent {
  @Override
  public String agentId() {
    return "narrative-agent";
  }

  @Override
  public Set<FindingCategory> supportedCategories() {
    return Set.of();
  }

  @Override
  public NarrativeResult narrate(
      DiagnosticReport report,
      List<AgentResult> agentResults,
      CorrelatedDiagnosis correlatedDiagnosis,
      AgentContext context) {
    List<Finding> prioritized = prioritizedFindings(report, agentResults);
    String top = prioritized.isEmpty() ? "no findings" : prioritized.getFirst().title();
    String executive = "Analyzed " + report.artifacts().size() + " artifact(s) with "
        + agentResults.size() + " reasoning agent(s); top priority is " + top + ".";
    String rootCause = correlatedDiagnosis != null && !correlatedDiagnosis.narrative().isBlank()
        ? correlatedDiagnosis.narrative()
        : "No cross-domain causal chain was identified; prioritize the highest severity domain findings.";
    List<String> actions = prioritized.stream()
        .flatMap(finding -> finding.remediations().stream())
        .map(remediation -> remediation.description())
        .distinct()
        .limit(5)
        .toList();
    double confidence = prioritized.stream().mapToDouble(Finding::confidence).average().orElse(0.0);
    return new NarrativeResult(executive, rootCause, prioritized, actions, confidence);
  }

  private static List<Finding> prioritizedFindings(DiagnosticReport report, List<AgentResult> results) {
    List<Finding> merged = new ArrayList<>(report.findings());
    results.stream()
        .flatMap(result -> result.enrichedFindings().stream())
        .forEach(enriched -> {
          merged.removeIf(existing -> existing.id().equals(enriched.id()));
          merged.add(enriched);
        });
    return merged.stream()
        .sorted(Comparator.comparing(Finding::severity).thenComparing(NarrativeAgent::severityRank).thenComparing(Finding::title))
        .toList();
  }

  private static int severityRank(Finding finding) {
    return switch (finding.severity()) {
      case CRITICAL -> 0;
      case HIGH -> 1;
      case MEDIUM -> 2;
      case LOW -> 3;
      case INFO -> 4;
    };
  }
}
