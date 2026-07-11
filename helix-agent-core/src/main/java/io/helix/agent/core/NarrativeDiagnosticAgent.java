package io.helix.agent.core;

import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.FindingCategory;
import java.util.List;
import java.util.Set;

public interface NarrativeDiagnosticAgent extends DiagnosticAgent {
  NarrativeResult narrate(
      DiagnosticReport report,
      List<AgentResult> agentResults,
      CorrelatedDiagnosis correlatedDiagnosis,
      AgentContext context);

  @Override
  default Set<FindingCategory> supportedCategories() {
    return Set.of();
  }

  @Override
  default AgentResult analyze(DiagnosticReport report, AgentContext context) {
    NarrativeResult narrative = narrate(report, context.agentResults(), context.correlatedDiagnosis(), context);
    return new AgentResult(agentId(), narrative.prioritizedFindings(), narrative.confidence(), List.of());
  }
}
