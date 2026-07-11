package io.helix.agent.gc;

import io.helix.agent.core.AgentContext;
import io.helix.agent.core.AgentResult;
import io.helix.agent.core.DiagnosticAgent;
import io.helix.agent.core.FindingEnricher;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import java.util.List;
import java.util.Set;

public final class GcAgent implements DiagnosticAgent {
  @Override
  public String agentId() {
    return "gc-agent";
  }

  @Override
  public Set<FindingCategory> supportedCategories() {
    return Set.of(FindingCategory.GC);
  }

  @Override
  public AgentResult analyze(DiagnosticReport report, AgentContext context) {
    List<Finding> findings = FindingEnricher.enrich(
        agentId(),
        report,
        context,
        FindingCategory.GC,
        "Pause issues, throughput degradation, allocation pressure, leak gradients, and JVM tuning",
        "prompts/gc-agent.md");
    double confidence = findings.isEmpty() ? 0.0 : findings.stream().mapToDouble(Finding::confidence).average().orElse(0.0);
    return new AgentResult(agentId(), findings, confidence, FindingEnricher.evidenceFor(findings));
  }
}
