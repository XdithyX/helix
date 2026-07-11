package io.helix.agent.core;

import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.FindingCategory;
import java.util.Set;

public interface DiagnosticAgent {
  String agentId();

  Set<FindingCategory> supportedCategories();

  AgentResult analyze(DiagnosticReport report, AgentContext context);
}
