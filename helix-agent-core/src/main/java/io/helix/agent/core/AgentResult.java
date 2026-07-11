package io.helix.agent.core;

import io.helix.core.model.Finding;
import io.helix.core.model.TimelineEvent;
import java.util.List;

public record AgentResult(
    String agentId,
    List<Finding> enrichedFindings,
    double confidence,
    List<TimelineEvent> evidence
) {
  public AgentResult {
    if (agentId == null || agentId.isBlank()) {
      throw new IllegalArgumentException("agentId must not be blank");
    }
    enrichedFindings = enrichedFindings == null ? List.of() : List.copyOf(enrichedFindings);
    if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
    }
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
  }

  public static AgentResult empty(String agentId) {
    return new AgentResult(agentId, List.of(), 0.0, List.of());
  }
}
