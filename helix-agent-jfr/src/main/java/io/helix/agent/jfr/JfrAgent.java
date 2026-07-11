package io.helix.agent.jfr;

import io.helix.agent.core.AgentContext;
import io.helix.agent.core.AgentResult;
import io.helix.agent.core.DiagnosticAgent;
import io.helix.agent.core.FindingEnricher;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import java.util.List;
import java.util.Set;

public final class JfrAgent implements DiagnosticAgent {
  @Override
  public String agentId() {
    return "jfr-agent";
  }

  @Override
  public Set<FindingCategory> supportedCategories() {
    return Set.of(FindingCategory.JIT);
  }

  @Override
  public AgentResult analyze(DiagnosticReport report, AgentContext context) {
    List<Finding> findings = FindingEnricher.enrich(
        agentId(),
        report,
        context,
        FindingCategory.JIT,
        "CPU hotspots, allocation hotspots, JFR lock contention, I/O latency, deoptimization, and virtual thread pinning",
        "prompts/jfr-agent.md");
    double confidence = findings.isEmpty() ? 0.0 : findings.stream().mapToDouble(Finding::confidence).average().orElse(0.0);
    return new AgentResult(agentId(), findings, confidence, FindingEnricher.evidenceFor(findings));
  }
}
