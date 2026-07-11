package io.helix.agent.core;

import io.helix.core.model.Finding;
import java.util.List;

public record NarrativeResult(
    String executiveSummary,
    String rootCauseNarrative,
    List<Finding> prioritizedFindings,
    List<String> recommendedActions,
    double confidence
) {
  public NarrativeResult {
    executiveSummary = executiveSummary == null ? "" : executiveSummary;
    rootCauseNarrative = rootCauseNarrative == null ? "" : rootCauseNarrative;
    prioritizedFindings = prioritizedFindings == null ? List.of() : List.copyOf(prioritizedFindings);
    recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
    if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
    }
  }

  public String asReportNarrative() {
    String actions = recommendedActions.isEmpty()
        ? "Recommended actions: none."
        : "Recommended actions: " + String.join("; ", recommendedActions) + ".";
    return "Executive summary: " + executiveSummary
        + "\nRoot cause narrative: " + rootCauseNarrative
        + "\n" + actions;
  }
}
