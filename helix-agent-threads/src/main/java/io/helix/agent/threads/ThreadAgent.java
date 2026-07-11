package io.helix.agent.threads;

import io.helix.agent.core.AgentContext;
import io.helix.agent.core.AgentResult;
import io.helix.agent.core.DiagnosticAgent;
import io.helix.agent.core.FindingEnricher;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import java.util.List;
import java.util.Set;

public final class ThreadAgent implements DiagnosticAgent {
  @Override
  public String agentId() {
    return "thread-agent";
  }

  @Override
  public Set<FindingCategory> supportedCategories() {
    return Set.of(FindingCategory.THREADING);
  }

  @Override
  public AgentResult analyze(DiagnosticReport report, AgentContext context) {
    List<Finding> findings = FindingEnricher.enrich(
        agentId(),
        report,
        context,
        FindingCategory.THREADING,
        "Deadlocks, lock contention, thread pool exhaustion, stuck threads, and state anomalies",
        "prompts/thread-agent.md");
    double confidence = findings.isEmpty() ? 0.0 : findings.stream().mapToDouble(Finding::confidence).average().orElse(0.0);
    return new AgentResult(agentId(), findings, confidence, FindingEnricher.evidenceFor(findings));
  }
}
