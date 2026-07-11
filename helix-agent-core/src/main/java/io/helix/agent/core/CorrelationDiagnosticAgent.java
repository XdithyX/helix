package io.helix.agent.core;

import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.FindingCategory;
import java.util.List;
import java.util.Set;

public interface CorrelationDiagnosticAgent extends DiagnosticAgent {
  CorrelatedDiagnosis correlate(
      DiagnosticReport report,
      List<AgentResult> agentResults,
      AgentContext context);

  @Override
  default Set<FindingCategory> supportedCategories() {
    return Set.copyOf(List.of(FindingCategory.MEMORY, FindingCategory.THREADING, FindingCategory.GC, FindingCategory.JIT));
  }

  @Override
  default AgentResult analyze(DiagnosticReport report, AgentContext context) {
    CorrelatedDiagnosis diagnosis = correlate(report, context.agentResults(), context);
    return new AgentResult(agentId(), List.of(), diagnosis.confidence(),
        diagnosis.links().stream().flatMap(link -> link.evidence().stream()).distinct().toList());
  }
}
