package io.helix.agent.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.agent.core.AgentContext;
import io.helix.agent.core.AgentResult;
import io.helix.agent.core.CorrelatedDiagnosis;
import io.helix.agent.core.CorrelationLink;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.JvmProfile;
import io.helix.core.model.TimelineEvent;
import io.helix.core.model.TimelineEventType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CorrelationReasoningAgentTest {
  @Test
  void createsRequestedCausalChainsFromAgentTimelineInputs() {
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    Finding leakFinding = finding("memory-leak", "Heap leak suspect");
    DiagnosticReport report = report(
        List.of(),
        List.of(
            event(base, TimelineEventType.GC_PAUSE, Duration.ofMillis(800)),
            event(base.plusMillis(400), TimelineEventType.THREAD_BLOCKED, Duration.ofMillis(200)),
            event(base.plusSeconds(10), TimelineEventType.ALLOCATION_PRESSURE, Duration.ZERO),
            event(base.plusSeconds(20), TimelineEventType.FULL_GC, Duration.ofSeconds(2)),
            event(base.plusSeconds(22), TimelineEventType.OOM, Duration.ZERO),
            event(base.plusSeconds(30), TimelineEventType.DEOPT, Duration.ZERO),
            event(base.plusSeconds(30).plusMillis(50), TimelineEventType.CPU_SPIKE, Duration.ZERO),
            event(base.plusSeconds(40), TimelineEventType.VIRTUAL_THREAD_PINNED, Duration.ofMillis(20), "thread", "vt-1"),
            event(base.plusSeconds(40).plusMillis(500), TimelineEventType.BLOCKING_IO, Duration.ofMillis(100), "thread", "vt-1")));

    CorrelatedDiagnosis diagnosis = new CorrelationAgent().correlate(
        report,
        List.of(new AgentResult("memory-agent", List.of(leakFinding), 0.8, List.of())),
        AgentContext.defaults());

    assertThat(diagnosis.narrative()).contains("5 causal link");
    assertThat(diagnosis.confidence()).isBetween(0.0, 1.0);
    assertThat(diagnosis.links())
        .extracting(CorrelationLink::ruleName)
        .containsExactly(
            "Agent_GC_Pause_Contention",
            "Agent_Allocation_Leak",
            "Agent_FullGC_OOM",
            "Agent_Deopt_CPU",
            "Agent_VThreadPinned_BlockingIO");
  }

  @Test
  void doesNotCreateCrossDomainConclusionsWithoutEvidence() {
    DiagnosticReport report = report(List.of(), List.of());

    CorrelatedDiagnosis diagnosis = new CorrelationAgent().correlate(report, List.of(), AgentContext.defaults());

    assertThat(diagnosis.links()).isEmpty();
    assertThat(diagnosis.confidence()).isZero();
    assertThat(diagnosis.narrative()).isEqualTo("No agent-level causal chain was identified.");
  }

  private static DiagnosticReport report(List<Finding> findings, List<TimelineEvent> timeline) {
    return new DiagnosticReport(
        "report",
        Instant.parse("2026-01-01T00:01:00Z"),
        List.of(),
        findings,
        timeline,
        CorrelationSummary.empty(),
        JvmProfile.unknown(),
        null);
  }

  private static Finding finding(String id, String title) {
    return new Finding(
        id,
        FindingSeverity.HIGH,
        FindingCategory.MEMORY,
        title,
        "explanation",
        0.8,
        List.of(),
        List.of(),
        List.of(),
        Map.of());
  }

  private static TimelineEvent event(Instant timestamp, TimelineEventType type, Duration duration) {
    return event(timestamp, type, duration, "source", "test");
  }

  private static TimelineEvent event(
      Instant timestamp,
      TimelineEventType type,
      Duration duration,
      String attributeKey,
      String attributeValue) {
    return new TimelineEvent(timestamp, "artifact", type, duration, Map.of(attributeKey, attributeValue));
  }
}
