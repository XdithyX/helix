package io.helix.agent.correlation;

import io.helix.agent.core.AgentContext;
import io.helix.agent.core.AgentResult;
import io.helix.agent.core.CorrelatedDiagnosis;
import io.helix.agent.core.CorrelationDiagnosticAgent;
import io.helix.agent.core.CorrelationLink;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.TimelineEvent;
import io.helix.core.model.TimelineEventType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class CorrelationAgent implements CorrelationDiagnosticAgent {
  @Override
  public String agentId() {
    return "correlation-agent";
  }

  @Override
  public Set<FindingCategory> supportedCategories() {
    return Set.of(FindingCategory.MEMORY, FindingCategory.THREADING, FindingCategory.GC, FindingCategory.JIT);
  }

  @Override
  public CorrelatedDiagnosis correlate(
      DiagnosticReport report,
      List<AgentResult> agentResults,
      AgentContext context) {
    List<TimelineEvent> timeline = report.timeline().stream()
        .sorted(Comparator.comparing(TimelineEvent::timestamp))
        .toList();
    List<CorrelationLink> links = new ArrayList<>();
    addGcPauseContention(timeline, links);
    addAllocationLeak(report, agentResults, timeline, links);
    addFullGcOom(timeline, links);
    addDeoptCpuSpike(timeline, links);
    addVirtualThreadPinningIo(timeline, links);
    double confidence = links.stream().mapToDouble(CorrelationLink::confidence).average().orElse(0.0);
    return new CorrelatedDiagnosis(narrative(links), links, confidence);
  }

  private static void addGcPauseContention(List<TimelineEvent> timeline, List<CorrelationLink> links) {
    for (TimelineEvent gc : events(timeline, TimelineEventType.GC_PAUSE)) {
      if (gc.duration() == null || gc.duration().toMillis() <= 500) {
        continue;
      }
      firstWithin(timeline, TimelineEventType.THREAD_BLOCKED, gc, Duration.ofSeconds(1))
          .ifPresent(blocked -> links.add(new CorrelationLink(
              "Agent_GC_Pause_Contention",
              gc,
              blocked,
              "A long GC pause was followed within 1 second by thread blocking.",
              0.86)));
    }
  }

  private static void addAllocationLeak(
      DiagnosticReport report,
      List<AgentResult> agentResults,
      List<TimelineEvent> timeline,
      List<CorrelationLink> links) {
    Optional<TimelineEvent> allocation = events(timeline, TimelineEventType.ALLOCATION_PRESSURE).stream().findFirst();
    List<Finding> findings = agentResults.stream()
        .flatMap(result -> result.enrichedFindings().stream())
        .toList();
    if (findings.isEmpty()) {
      findings = report.findings();
    }
    boolean hasLeak = findings.stream()
        .filter(finding -> finding.category() == FindingCategory.MEMORY)
        .anyMatch(finding -> containsAny(finding.title(), "leak", "dominator", "byte[]", "classloader"));
    if (allocation.isPresent() && hasLeak) {
      links.add(new CorrelationLink(
          "Agent_Allocation_Leak",
          allocation.get(),
          allocation.get(),
          "Allocation pressure appears in the same report as memory-retention findings.",
          0.74));
    }
  }

  private static void addFullGcOom(List<TimelineEvent> timeline, List<CorrelationLink> links) {
    for (TimelineEvent fullGc : events(timeline, TimelineEventType.FULL_GC)) {
      firstWithin(timeline, TimelineEventType.OOM, fullGc, Duration.ofMinutes(5))
          .ifPresent(oom -> links.add(new CorrelationLink(
              "Agent_FullGC_OOM",
              fullGc,
              oom,
              "A Full GC was followed by an OOM event within 5 minutes.",
              0.88)));
    }
  }

  private static void addDeoptCpuSpike(List<TimelineEvent> timeline, List<CorrelationLink> links) {
    for (TimelineEvent deopt : events(timeline, TimelineEventType.DEOPT)) {
      firstWithin(timeline, TimelineEventType.CPU_SPIKE, deopt, Duration.ofMillis(100))
          .ifPresent(cpu -> links.add(new CorrelationLink(
              "Agent_Deopt_CPU",
              deopt,
              cpu,
              "A deoptimization event was followed by a CPU spike within 100ms.",
              0.76)));
    }
  }

  private static void addVirtualThreadPinningIo(List<TimelineEvent> timeline, List<CorrelationLink> links) {
    for (TimelineEvent pinned : events(timeline, TimelineEventType.VIRTUAL_THREAD_PINNED)) {
      timeline.stream()
          .filter(event -> event.type() == TimelineEventType.BLOCKING_IO)
          .filter(event -> sameThread(pinned, event))
          .filter(event -> Math.abs(Duration.between(pinned.timestamp(), event.timestamp()).toMillis()) <= 1_000)
          .findFirst()
          .ifPresent(io -> links.add(new CorrelationLink(
              "Agent_VThreadPinned_BlockingIO",
              pinned,
              io,
              "Virtual thread pinning happened near blocking I/O on the same thread.",
              0.86)));
    }
  }

  private static List<TimelineEvent> events(List<TimelineEvent> timeline, TimelineEventType type) {
    return timeline.stream().filter(event -> event.type() == type).toList();
  }

  private static Optional<TimelineEvent> firstWithin(
      List<TimelineEvent> timeline,
      TimelineEventType type,
      TimelineEvent start,
      Duration window) {
    return timeline.stream()
        .filter(event -> event.type() == type)
        .filter(event -> !event.timestamp().isBefore(start.timestamp()))
        .filter(event -> Duration.between(start.timestamp(), event.timestamp()).compareTo(window) <= 0)
        .findFirst();
  }

  private static boolean sameThread(TimelineEvent left, TimelineEvent right) {
    Object leftThread = left.attributes().get("thread");
    Object rightThread = right.attributes().get("thread");
    return leftThread != null && leftThread.equals(rightThread);
  }

  private static boolean containsAny(String text, String... tokens) {
    String lower = text.toLowerCase();
    for (String token : tokens) {
      if (lower.contains(token)) {
        return true;
      }
    }
    return false;
  }

  private static String narrative(List<CorrelationLink> links) {
    if (links.isEmpty()) {
      return "No agent-level causal chain was identified.";
    }
    CorrelationLink first = links.getFirst();
    return "Agent correlation identified " + links.size() + " causal link(s). First link: "
        + first.explanation();
  }
}
