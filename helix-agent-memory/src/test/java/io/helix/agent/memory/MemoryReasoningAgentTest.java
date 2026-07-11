package io.helix.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.agent.core.AgentContext;
import io.helix.agent.core.AgentResult;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.JvmProfile;
import io.helix.core.spi.ReasoningProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MemoryReasoningAgentTest {
  @Test
  void processesOnlyMemoryFindings() {
    DiagnosticReport report = report(
        finding("memory", FindingCategory.MEMORY),
        finding("thread", FindingCategory.THREADING));

    AgentResult result = new MemoryAgent().analyze(report, AgentContext.defaults());

    assertThat(result.enrichedFindings()).hasSize(1);
    assertThat(result.enrichedFindings().getFirst().id()).isEqualTo("memory");
    assertThat(result.enrichedFindings().getFirst().rawData()).containsEntry("agent.id", "memory-agent");
  }

  @Test
  void usesSanitizedMemoryAgentPromptWhenReasoningProviderIsAvailable() {
    AtomicReference<String> prompt = new AtomicReference<>();
    ReasoningProvider provider = new CapturingReasoningProvider(prompt);
    DiagnosticReport report = report(
        finding("memory", FindingCategory.MEMORY),
        finding("thread", FindingCategory.THREADING));
    AgentContext context = new AgentContext(
        JvmProfile.unknown(),
        null,
        provider,
        List.of(),
        null,
        Map.of());

    AgentResult result = new MemoryAgent().analyze(report, context);

    assertThat(prompt.get()).contains("You are the Helix MemoryAgent.");
    assertThat(prompt.get()).contains("memory");
    assertThat(prompt.get()).doesNotContain("thread-agent");
    assertThat(result.enrichedFindings().getFirst().rawData())
        .containsEntry("agent.reasoning", "mocked memory reasoning");
  }

  @Test
  void recordsReasoningProviderFailuresWithoutDroppingAgentEnrichment() {
    ReasoningProvider provider = new FailingReasoningProvider();
    DiagnosticReport report = report(finding("memory", FindingCategory.MEMORY));
    AgentContext context = new AgentContext(
        JvmProfile.unknown(),
        null,
        provider,
        List.of(),
        null,
        Map.of());

    AgentResult result = new MemoryAgent().analyze(report, context);

    assertThat(result.enrichedFindings().getFirst().rawData())
        .containsEntry("agent.enriched", true)
        .containsEntry("agent.reasoning.error", "IllegalStateException: Gemini returned HTTP 404")
        .doesNotContainKey("agent.reasoning");
  }

  private static DiagnosticReport report(Finding... findings) {
    return new DiagnosticReport("report", Instant.EPOCH, List.of(), List.of(findings), List.of(),
        CorrelationSummary.empty(), JvmProfile.unknown(), null);
  }

  private static Finding finding(String id, FindingCategory category) {
    return new Finding(id, FindingSeverity.HIGH, category, id, id, 0.8, List.of(), List.of(), List.of(), Map.of());
  }

  private record CapturingReasoningProvider(AtomicReference<String> prompt) implements ReasoningProvider {
    @Override
    public String complete(String prompt) {
      this.prompt.set(prompt);
      return "mocked memory reasoning";
    }

    @Override
    public String explain(Finding finding, DiagnosticReport context) {
      return "";
    }

    @Override
    public String narrateSummary(DiagnosticReport report) {
      return "";
    }

    @Override
    public boolean isAvailable() {
      return true;
    }
  }

  private static final class FailingReasoningProvider implements ReasoningProvider {
    @Override
    public String complete(String prompt) {
      throw new IllegalStateException("Gemini returned HTTP 404");
    }

    @Override
    public String explain(Finding finding, DiagnosticReport context) {
      return "";
    }

    @Override
    public String narrateSummary(DiagnosticReport report) {
      return "";
    }

    @Override
    public boolean isAvailable() {
      return true;
    }
  }
}
