package io.helix.correlation;

import io.helix.core.model.ArtifactType;
import io.helix.core.model.CorrelatedEvent;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingReliability;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.Remediation;
import io.helix.core.model.RemediationType;
import io.helix.core.model.TimelineEvent;
import io.helix.core.model.TimelineEventType;
import io.helix.core.model.heap.HeapSnapshotData;
import io.helix.core.spi.CorrelationResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class DroolsCorrelationContext {
  private static final String HEAP_DATA_KEY = "heapSnapshot";

  private final List<ParsedArtifact> artifacts;
  private final List<TimelineEvent> timeline;
  private final List<String> bundledRuleNames;
  private final List<Finding> findings = new ArrayList<>();
  private final List<CorrelatedEvent> correlated = new ArrayList<>();
  private final Set<String> matchedKeys = new LinkedHashSet<>();

  public DroolsCorrelationContext(
      List<ParsedArtifact> artifacts,
      List<TimelineEvent> timeline,
      List<String> bundledRuleNames) {
    this.artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    this.timeline = timeline == null ? List.of() : List.copyOf(timeline);
    this.bundledRuleNames = bundledRuleNames == null ? List.of() : List.copyOf(bundledRuleNames);
  }

  public static boolean withinAfter(TimelineEvent start, TimelineEvent candidate, long windowMillis) {
    if (start == null || candidate == null || candidate.timestamp().isBefore(start.timestamp())) {
      return false;
    }
    return Duration.between(start.timestamp(), candidate.timestamp()).toMillis() <= windowMillis;
  }

  public static boolean withinEither(TimelineEvent left, TimelineEvent right, long windowMillis) {
    if (left == null || right == null) {
      return false;
    }
    return Math.abs(Duration.between(left.timestamp(), right.timestamp()).toMillis()) <= windowMillis;
  }

  public static boolean sameThread(TimelineEvent left, TimelineEvent right) {
    String leftThread = attr(left, "thread");
    String rightThread = attr(right, "thread");
    return !leftThread.isBlank() && leftThread.equals(rightThread);
  }

  public boolean hasHeapLeakSuspects() {
    return heapWithLeakSuspects().isPresent();
  }

  public int systemGcCount() {
    return events(TimelineEventType.SYSTEM_GC).size();
  }

  public void addGcPauseThreadContention(TimelineEvent gc, TimelineEvent blocked) {
    String rule = "GCPressure_ThreadContention_Correlation";
    if (!remember(rule, gc, blocked)) {
      return;
    }
    String lock = attr(blocked, "lock", "monitor");
    String explanation = "A " + gc.duration().toMillis() + "ms GC pause at " + gc.timestamp()
        + " was followed within 1 second by thread contention"
        + (lock.isBlank() ? "." : " on " + lock + ".");
    correlated.add(new CorrelatedEvent(rule, gc, blocked, 0.86, explanation));
    findings.add(finding(
        "correlation-gc-thread-contention-" + findings.size(),
        FindingSeverity.HIGH,
        FindingCategory.THREADING,
        "Thread contention spike follows GC pause",
        explanation,
        List.of(gc, blocked),
        "Inspect lock owners immediately after long GC pauses.",
        RemediationType.CODE_CHANGE,
        mapOf("rule", rule)));
  }

  public void addAllocationPressureHeapLeak(TimelineEvent allocation) {
    String rule = "AllocationPressure_HeapLeak_Correlation";
    if (!rememberRule(rule)) {
      return;
    }
    Optional<ParsedArtifact> heapWithLeak = heapWithLeakSuspects();
    if (heapWithLeak.isEmpty()) {
      return;
    }
    TimelineEvent firstAllocation = events(TimelineEventType.ALLOCATION_PRESSURE).getFirst();
    ParsedArtifact heap = heapWithLeak.get();
    TimelineEvent heapEvent = new TimelineEvent(
        heap.artifact().capturedAt(),
        heap.artifact().id(),
        TimelineEventType.ALLOCATION_PRESSURE,
        null,
        mapOf("heap.leak.path", "present"));
    String explanation = "Allocation pressure was observed in the same analysis window as heap leak suspects in "
        + heap.artifact().id() + ".";
    correlated.add(new CorrelatedEvent(rule, firstAllocation, heapEvent, 0.74, explanation));
    findings.add(finding(
        "correlation-allocation-heap-leak",
        FindingSeverity.HIGH,
        FindingCategory.MEMORY,
        "Allocation pressure aligns with heap leak suspects",
        explanation,
        List.of(firstAllocation, heapEvent),
        "Compare allocation hotspots with the heap dump retention paths.",
        RemediationType.CODE_CHANGE,
        mapOf("rule", rule)));
  }

  public void addFullGcOom(TimelineEvent fullGc, TimelineEvent oom) {
    String rule = "FullGC_OOM_Correlation";
    if (!remember(rule, fullGc, oom)) {
      return;
    }
    String explanation = "A Full GC at " + fullGc.timestamp()
        + " was followed by an OOM event within 5 minutes.";
    correlated.add(new CorrelatedEvent(rule, fullGc, oom, 0.88, explanation));
    findings.add(finding(
        "correlation-full-gc-oom-" + findings.size(),
        FindingSeverity.CRITICAL,
        FindingCategory.MEMORY,
        "OOM follows Full GC",
        explanation,
        List.of(fullGc, oom),
        "Treat this as memory exhaustion until heap evidence proves otherwise.",
        RemediationType.CONFIG_CHANGE,
        mapOf("rule", rule)));
  }

  public void addThreadPoolGcPressure(TimelineEvent pool, TimelineEvent gc) {
    String rule = "ThreadPoolExhaustion_GCPressure_Correlation";
    if (!remember(rule, pool, gc)) {
      return;
    }
    String explanation = "Thread pool exhaustion and GC pressure occurred in the same 1-second window.";
    correlated.add(new CorrelatedEvent(rule, pool, gc, 0.78, explanation));
    findings.add(finding(
        "correlation-pool-gc-" + findings.size(),
        FindingSeverity.HIGH,
        FindingCategory.THREADING,
        "Thread pool exhaustion coincides with GC pressure",
        explanation,
        List.of(pool, gc),
        "Check whether blocked worker threads are retaining allocation-heavy work.",
        RemediationType.ARCHITECTURAL,
        mapOf("rule", rule)));
  }

  public void addDeoptCpuSpike(TimelineEvent deopt, TimelineEvent cpu) {
    String rule = "Deoptimization_CPUSpike_Correlation";
    if (!remember(rule, deopt, cpu)) {
      return;
    }
    String method = attr(deopt, "method");
    String explanation = "A deoptimization event" + (method.isBlank() ? "" : " in " + method)
        + " was followed by a CPU spike within 100ms.";
    correlated.add(new CorrelatedEvent(rule, deopt, cpu, 0.76, explanation));
    findings.add(finding(
        "correlation-deopt-cpu-" + findings.size(),
        FindingSeverity.MEDIUM,
        FindingCategory.JIT,
        "CPU spike follows deoptimization",
        explanation,
        List.of(deopt, cpu),
        "Inspect repeated deopt methods before tuning compiler flags.",
        RemediationType.CODE_CHANGE,
        mapOf("rule", rule)));
  }

  public void addRepeatedSystemGc() {
    String rule = "RepeatedSystemGC_FrameworkTrigger_Correlation";
    List<TimelineEvent> systemGc = events(TimelineEventType.SYSTEM_GC);
    if (systemGc.size() < 2) {
      return;
    }
    TimelineEvent first = systemGc.getFirst();
    TimelineEvent last = systemGc.getLast();
    if (!remember(rule, first, last)) {
      return;
    }
    String explanation = systemGc.size()
        + " explicit System.gc() events were observed; this often comes from JMX operations or Spring Boot Actuator heap endpoints.";
    correlated.add(new CorrelatedEvent(rule, first, last, 0.72, explanation));
    findings.add(finding(
        "correlation-repeated-system-gc",
        FindingSeverity.HIGH,
        FindingCategory.GC,
        "Repeated System.gc() suggests external trigger",
        explanation,
        List.of(first, last),
        "Audit JMX clients and Spring Boot Actuator heap/memory endpoints.",
        RemediationType.CONFIG_CHANGE,
        mapOf("rule", rule, "count", systemGc.size())));
  }

  public void addHumongousAllocation(TimelineEvent humongous) {
    String rule = "HumongousAllocation_RegionSizing_Correlation";
    if (!rememberRule(rule)) {
      return;
    }
    List<TimelineEvent> events = events(TimelineEventType.HUMONGOUS_ALLOCATION);
    TimelineEvent first = events.getFirst();
    TimelineEvent last = events.getLast();
    String explanation = events.size()
        + " humongous allocation event(s) indicate possible G1 region size or large-object allocation issues.";
    correlated.add(new CorrelatedEvent(rule, first, last, 0.70, explanation));
    findings.add(finding(
        "correlation-humongous-region-" + findings.size(),
        FindingSeverity.MEDIUM,
        FindingCategory.GC,
        "Humongous allocation suggests region sizing review",
        explanation,
        List.of(first, last),
        "Confirm object sizes before changing -XX:G1HeapRegionSize.",
        RemediationType.JVM_FLAG,
        mapOf("rule", rule, "count", events.size())));
  }

  public void addVirtualThreadPinningBlockingIo(TimelineEvent pinned, TimelineEvent io) {
    String rule = "VirtualThreadPinning_BlockingIO_Correlation";
    if (!remember(rule, pinned, io)) {
      return;
    }
    String target = attr(io, "io.target");
    String explanation = "A virtual thread pinning event correlates with blocking I/O"
        + (target.isBlank() ? "." : " on " + target + ".");
    correlated.add(new CorrelatedEvent(rule, pinned, io, 0.86, explanation));
    findings.add(finding(
        "correlation-vthread-io-" + findings.size(),
        FindingSeverity.HIGH,
        FindingCategory.THREADING,
        "Virtual thread pinning correlates with blocking I/O",
        explanation,
        List.of(pinned, io),
        "Move blocking I/O outside pinned synchronized/native regions.",
        RemediationType.CODE_CHANGE,
        mapOf("rule", rule)));
  }

  public CorrelationResult toResult(String engineName, String fallbackReason) {
    return new CorrelationResult(summary(engineName, fallbackReason), List.copyOf(findings), timeline);
  }

  private CorrelationSummary summary(String engineName, String fallbackReason) {
    Map<String, Object> metrics = mapOf(
        "rulesMatched", correlated.size(),
        "timelineEvents", timeline.size(),
        "bundledDrlRules", bundledRuleNames,
        "correlationEngine", engineName,
        "fallbackReason", fallbackReason);
    if (correlated.isEmpty()) {
      return new CorrelationSummary(
          "No Phase 7 cross-artifact correlations matched the current timeline.",
          List.of(),
          metrics);
    }
    CorrelatedEvent first = correlated.getFirst();
    String narrative = "At " + first.primaryEvent().timestamp() + ", "
        + first.explanation()
        + " Across the analysis window, Helix matched " + correlated.size()
        + " Phase 7 correlation rule(s), suggesting the most likely sequence is: "
        + correlated.stream().limit(3).map(CorrelatedEvent::ruleName).reduce((left, right) -> left + " -> " + right).orElse(first.ruleName())
        + ".";
    return new CorrelationSummary(narrative, List.copyOf(correlated), metrics);
  }

  private Optional<ParsedArtifact> heapWithLeakSuspects() {
    return artifacts.stream()
        .filter(artifact -> artifact.artifact().type() == ArtifactType.HEAP_DUMP)
        .filter(artifact -> {
          Object data = artifact.data().get(HEAP_DATA_KEY);
          return data instanceof HeapSnapshotData heap && !heap.leakSuspects().isEmpty();
        })
        .findFirst();
  }

  private List<TimelineEvent> events(TimelineEventType type) {
    return timeline.stream().filter(event -> event.type() == type).toList();
  }

  private boolean remember(String rule, TimelineEvent primary, TimelineEvent related) {
    return matchedKeys.add(rule + ":" + eventKey(primary) + ":" + eventKey(related));
  }

  private boolean rememberRule(String rule) {
    return matchedKeys.add(rule);
  }

  private static String eventKey(TimelineEvent event) {
    return event.artifactId() + ":" + event.type() + ":" + event.timestamp();
  }

  private static Finding finding(
      String id,
      FindingSeverity severity,
      FindingCategory category,
      String title,
      String explanation,
      List<TimelineEvent> evidence,
      String remediation,
      RemediationType remediationType,
      Map<String, Object> rawData) {
    return new Finding(
        id,
        severity,
        category,
        title,
        explanation,
        0.82,
        evidence.stream().map(TimelineEvent::artifactId).distinct().toList(),
        evidence,
        List.of(new Remediation(remediation, remediationType, "", explanation)),
        rawData)
        .withReliability(FindingReliability.HEURISTIC);
  }

  private static String attr(TimelineEvent event, String... names) {
    if (event == null) {
      return "";
    }
    for (String name : names) {
      Object value = event.attributes().get(name);
      if (value != null && !value.toString().isBlank()) {
        return value.toString();
      }
    }
    return "";
  }

  private static Map<String, Object> mapOf(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      Object value = values[i + 1];
      if (value != null) {
        result.put((String) values[i], value);
      }
    }
    return result;
  }
}
