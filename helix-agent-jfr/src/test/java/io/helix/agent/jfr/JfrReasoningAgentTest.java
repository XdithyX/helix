package io.helix.agent.jfr;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.agent.core.AgentContext;
import io.helix.agent.core.AgentResult;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.JvmProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JfrReasoningAgentTest {
  @Test
  void processesOnlyJitFindings() {
    DiagnosticReport report = report(
        finding("jit", FindingCategory.JIT),
        finding("gc", FindingCategory.GC));

    AgentResult result = new JfrAgent().analyze(report, AgentContext.defaults());

    assertThat(result.enrichedFindings()).hasSize(1);
    assertThat(result.enrichedFindings().getFirst().id()).isEqualTo("jit");
    assertThat(result.enrichedFindings().getFirst().rawData()).containsEntry("agent.id", "jfr-agent");
  }

  private static DiagnosticReport report(Finding... findings) {
    return new DiagnosticReport("report", Instant.EPOCH, List.of(), List.of(findings), List.of(),
        CorrelationSummary.empty(), JvmProfile.unknown(), null);
  }

  private static Finding finding(String id, FindingCategory category) {
    return new Finding(id, FindingSeverity.HIGH, category, id, id, 0.8, List.of(), List.of(), List.of(), Map.of());
  }
}
