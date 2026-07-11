package io.helix.agent.core;

import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.FindingCategory;
import java.util.Set;

public final class TestServiceAgent implements DiagnosticAgent {
  @Override
  public String agentId() {
    return "test-service-agent";
  }

  @Override
  public Set<FindingCategory> supportedCategories() {
    return Set.of(FindingCategory.MEMORY);
  }

  @Override
  public AgentResult analyze(DiagnosticReport report, AgentContext context) {
    return AgentResult.empty(agentId());
  }
}
