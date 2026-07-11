package io.helix.agent.narrative;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.agent.core.AgentContext;
import io.helix.agent.core.AgentResult;
import io.helix.agent.core.CorrelatedDiagnosis;
import io.helix.agent.core.CorrelationLink;
import io.helix.agent.core.NarrativeResult;
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
import org.junit.jupiter.api.Test;

class NarrativeAgentTest {
  @Test
  void generatesDeterministicNarrativeAndPrioritizedFindings() {
    Finding low = finding("low", FindingSeverity.LOW, "Minor duplicate strings", "Tune later");
    Finding high = finding("high", FindingSeverity.HIGH, "Large byte arrays", "Reduce buffers");
    Finding critical = finding("critical", FindingSeverity.CRITICAL, "Application deadlock", "Break lock cycle");
    Finding enrichedHigh = new Finding(
        high.id(),
        high.severity(),
        high.category(),
        "Large byte arrays",
        high.explanation(),
        high.confidence(),
        high.artifactIds(),
        high.evidence(),
        high.remediations(),
        Map.of("agent.id", "memory-agent"));
    TimelineEvent event = new TimelineEvent(
        Instant.parse("2026-01-01T00:00:00Z"),
        "artifact",
        TimelineEventType.THREAD_BLOCKED,
        Duration.ofSeconds(1),
        Map.of());
    CorrelatedDiagnosis diagnosis = new CorrelatedDiagnosis(
        "Deadlock aligns with memory pressure.",
        List.of(new CorrelationLink("rule", event, event, "linked", 0.8)),
        0.8);

    NarrativeResult first = new NarrativeAgent().narrate(
        report(List.of(low, high, critical)),
        List.of(new AgentResult("memory-agent", List.of(enrichedHigh), 0.8, List.of(event))),
        diagnosis,
        AgentContext.defaults());
    NarrativeResult second = new NarrativeAgent().narrate(
        report(List.of(low, high, critical)),
        List.of(new AgentResult("memory-agent", List.of(enrichedHigh), 0.8, List.of(event))),
        diagnosis,
        AgentContext.defaults());

    assertThat(first).isEqualTo(second);
    assertThat(first.executiveSummary())
        .isEqualTo("Analyzed 0 artifact(s) with 1 reasoning agent(s); top priority is Application deadlock.");
    assertThat(first.rootCauseNarrative()).isEqualTo("Deadlock aligns with memory pressure.");
    assertThat(first.prioritizedFindings())
        .extracting(Finding::id)
        .containsExactly("critical", "high", "low");
    assertThat(first.prioritizedFindings().get(1).rawData()).containsEntry("agent.id", "memory-agent");
    assertThat(first.recommendedActions()).containsExactly("Break lock cycle", "Reduce buffers", "Tune later");
  }

  private static DiagnosticReport report(List<Finding> findings) {
    return new DiagnosticReport(
        "report",
        Instant.parse("2026-01-01T00:00:01Z"),
        List.of(),
        findings,
        List.of(),
        CorrelationSummary.empty(),
        JvmProfile.unknown(),
        null);
  }

  private static Finding finding(String id, FindingSeverity severity, String title, String action) {
    return new Finding(
        id,
        severity,
        FindingCategory.MEMORY,
        title,
        "explanation",
        0.8,
        List.of(),
        List.of(),
        List.of(new Remediation(action, RemediationType.CODE_CHANGE, "", "")),
        Map.of());
  }
}
