package io.helix.agent.memory;

import io.helix.agent.core.AgentContext;
import io.helix.agent.core.AgentResult;
import io.helix.agent.core.DiagnosticAgent;
import io.helix.agent.core.FindingEnricher;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import java.util.List;
import java.util.Set;

public final class MemoryAgent implements DiagnosticAgent {
  @Override
  public String agentId() {
    return "memory-agent";
  }

  @Override
  public Set<FindingCategory> supportedCategories() {
    return Set.of(FindingCategory.MEMORY);
  }

  @Override
  public AgentResult analyze(DiagnosticReport report, AgentContext context) {
    List<Finding> findings = FindingEnricher.enrich(
        agentId(),
        report,
        context,
        FindingCategory.MEMORY,
        "Leak suspects, dominators, duplicate strings, classloader leaks, and byte[] retention",
        "prompts/memory-agent.md");
    double confidence = findings.isEmpty() ? 0.0 : averageConfidence(findings);
    return new AgentResult(agentId(), findings, confidence, FindingEnricher.evidenceFor(findings));
  }

  private static double averageConfidence(List<Finding> findings) {
    return findings.stream().mapToDouble(Finding::confidence).average().orElse(0.0);
  }
}
