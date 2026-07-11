package io.helix.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.JvmProfile;
import io.helix.core.model.Remediation;
import io.helix.core.model.RemediationType;
import io.helix.core.model.TimelineEvent;
import io.helix.core.model.TimelineEventType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentOrchestratorTest {
  @Test
  void runsDomainAgentsConcurrentlyAndPreservesReportSchema() {
    DiagnosticReport report = report(List.of(finding("f1", FindingCategory.MEMORY)));
    AgentRegistry registry = new AgentRegistry(List.of(
        new SlowAgent("slow-a", FindingCategory.MEMORY),
        new SlowAgent("slow-b", FindingCategory.THREADING),
        new TestCorrelationAgent(),
        new TestNarrativeAgent()));

    long started = System.nanoTime();
    DiagnosticReport result = new AgentOrchestrator(registry).analyze(report, AgentContext.defaults());
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertThat(elapsedMillis).isLessThan(550);
    assertThat(result.reportId()).isEqualTo(report.reportId());
    assertThat(result.generatedAt()).isEqualTo(report.generatedAt());
    assertThat(result.findings()).hasSize(1);
    assertThat(result.llmNarrative()).contains("Executive summary");
    assertThat(result.correlationSummary().metrics()).containsEntry("agentCorrelationLinks", 1);
  }

  @Test
  void discoversAgentsWithServiceLoader() {
    AgentRegistry registry = AgentRegistry.load();

    assertThat(registry.agents())
        .extracting(DiagnosticAgent::agentId)
        .contains("test-service-agent");
  }

  private static DiagnosticReport report(List<Finding> findings) {
    TimelineEvent event = new TimelineEvent(
        Instant.parse("2026-01-01T00:00:00Z"),
        "artifact",
        TimelineEventType.ALLOCATION_PRESSURE,
        Duration.ZERO,
        Map.of());
    return new DiagnosticReport(
        "report",
        Instant.parse("2026-01-01T00:00:01Z"),
        List.of(),
        findings,
        List.of(event),
        CorrelationSummary.empty(),
        JvmProfile.unknown(),
        null);
  }

  private static Finding finding(String id, FindingCategory category) {
    return new Finding(
        id,
        FindingSeverity.HIGH,
        category,
        "Finding " + id,
        "Explanation",
        0.8,
        List.of("artifact"),
        List.of(),
        List.of(new Remediation("Fix", RemediationType.CODE_CHANGE, "", "")),
        Map.of());
  }

  private record SlowAgent(String agentId, FindingCategory category) implements DiagnosticAgent {
    @Override
    public Set<FindingCategory> supportedCategories() {
      return Set.of(category);
    }

    @Override
    public AgentResult analyze(DiagnosticReport report, AgentContext context) {
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return new AgentResult(agentId, report.findings(), 0.8, List.of());
    }
  }

  private static final class TestCorrelationAgent implements CorrelationDiagnosticAgent {
    @Override
    public String agentId() {
      return "test-correlation-agent";
    }

    @Override
    public CorrelatedDiagnosis correlate(
        DiagnosticReport report,
        List<AgentResult> agentResults,
        AgentContext context) {
      TimelineEvent event = report.timeline().getFirst();
      return new CorrelatedDiagnosis(
          "test causal chain",
          List.of(new CorrelationLink("test-rule", event, event, "linked", 0.8)),
          0.8);
    }
  }

  private static final class TestNarrativeAgent implements NarrativeDiagnosticAgent {
    @Override
    public String agentId() {
      return "test-narrative-agent";
    }

    @Override
    public NarrativeResult narrate(
        DiagnosticReport report,
        List<AgentResult> agentResults,
        CorrelatedDiagnosis correlatedDiagnosis,
        AgentContext context) {
      return new NarrativeResult(
          "done",
          correlatedDiagnosis.narrative(),
          report.findings(),
          List.of("Fix"),
          0.8);
    }
  }
}
