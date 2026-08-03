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
import io.helix.core.spi.Correlator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RuleBasedCorrelator implements Correlator {
  private static final String HEAP_DATA_KEY = "heapSnapshot";

  @Override
  public CorrelationResult correlate(List<ParsedArtifact> artifacts, List<TimelineEvent> timeline) {
    List<ParsedArtifact> safeArtifacts = artifacts == null ? List.of() : artifacts;
    List<String> bundledRuleNames = DrlRuleCatalog.bundledRuleNames();
    List<TimelineEvent> sorted = timeline == null ? List.of() : timeline.stream()
        .sorted(Comparator.comparing(TimelineEvent::timestamp))
        .toList();
    try {
      return new DroolsCorrelationEngine().correlate(safeArtifacts, sorted, bundledRuleNames);
    } catch (RuntimeException e) {
      return correlateWithJavaFallback(safeArtifacts, sorted, bundledRuleNames, e.toString());
    }
  }

  private static CorrelationResult correlateWithJavaFallback(
      List<ParsedArtifact> artifacts,
      List<TimelineEvent> sorted,
      List<String> bundledRuleNames,
      String fallbackReason) {
    List<Finding> findings = new ArrayList<>();
    List<CorrelatedEvent> correlated = new ArrayList<>();

    applyGcPauseThreadContention(sorted, findings, correlated);
    applyAllocationPressureHeapLeak(artifacts, sorted, findings, correlated);
    applyFullGcOom(sorted, findings, correlated);
    applyThreadPoolGcPressure(sorted, findings, correlated);
    applyDeoptCpuSpike(sorted, findings, correlated);
    applyRepeatedSystemGc(sorted, findings, correlated);
    applyHumongousAllocation(sorted, findings, correlated);
    applyVirtualThreadPinningBlockingIo(sorted, findings, correlated);

    return new CorrelationResult(
        summary(correlated, sorted, bundledRuleNames, "java-fallback", fallbackReason),
        findings,
        sorted);
  }

  private static void applyGcPauseThreadContention(
      List<TimelineEvent> timeline,
      List<Finding> findings,
      List<CorrelatedEvent> correlated) {
    for (TimelineEvent gc : events(timeline, TimelineEventType.GC_PAUSE)) {
      if (gc.duration() == null || gc.duration().compareTo(Duration.ofMillis(500)) <= 0) {
        continue;
      }
      Optional<TimelineEvent> blocked = firstWithin(timeline, TimelineEventType.THREAD_BLOCKED, gc.timestamp(), Duration.ofSeconds(1));
      if (blocked.isEmpty()) {
        continue;
      }
      TimelineEvent related = blocked.get();
      String lock = attr(related, "lock", "monitor");
      String explanation = "A " + gc.duration().toMillis() + "ms GC pause at " + gc.timestamp()
          + " was followed within 1 second by thread contention"
          + (lock.isBlank() ? "." : " on " + lock + ".");
      correlated.add(new CorrelatedEvent("GCPressure_ThreadContention_Correlation", gc, related, 0.86, explanation));
      findings.add(finding(
          "correlation-gc-thread-contention-" + findings.size(),
          FindingSeverity.HIGH,
          FindingCategory.THREADING,
          "Thread contention spike follows GC pause",
          explanation,
          List.of(gc, related),
          "Inspect lock owners immediately after long GC pauses.",
          RemediationType.CODE_CHANGE,
          mapOf("rule", "GCPressure_ThreadContention_Correlation")));
    }
  }

  private static void applyAllocationPressureHeapLeak(
      List<ParsedArtifact> artifacts,
      List<TimelineEvent> timeline,
      List<Finding> findings,
      List<CorrelatedEvent> correlated) {
    Optional<TimelineEvent> allocation = events(timeline, TimelineEventType.ALLOCATION_PRESSURE).stream().findFirst();
    if (allocation.isEmpty()) {
      return;
    }
    Optional<ParsedArtifact> heapWithLeak = artifacts.stream()
        .filter(artifact -> artifact.artifact().type() == ArtifactType.HEAP_DUMP)
        .filter(artifact -> {
          Object data = artifact.data().get(HEAP_DATA_KEY);
          return data instanceof HeapSnapshotData heap && !heap.leakSuspects().isEmpty();
        })
        .findFirst();
    if (heapWithLeak.isEmpty()) {
      return;
    }
    ParsedArtifact heap = heapWithLeak.get();
    TimelineEvent heapEvent = new TimelineEvent(
        heap.artifact().capturedAt(),
        heap.artifact().id(),
        TimelineEventType.ALLOCATION_PRESSURE,
        null,
        mapOf("heap.leak.path", "present"));
    String explanation = "Allocation pressure was observed in the same analysis window as large retained heap graphs in "
        + heap.artifact().id() + ".";
    correlated.add(new CorrelatedEvent("AllocationPressure_HeapLeak_Correlation", allocation.get(), heapEvent, 0.74, explanation));
    findings.add(finding(
        "correlation-allocation-heap-leak",
        FindingSeverity.HIGH,
        FindingCategory.MEMORY,
        "Allocation pressure aligns with retained heap graphs",
        explanation,
        List.of(allocation.get(), heapEvent),
        "Compare allocation hotspots with the heap dump retention paths.",
        RemediationType.CODE_CHANGE,
        mapOf("rule", "AllocationPressure_HeapLeak_Correlation")));
  }

  private static void applyFullGcOom(
      List<TimelineEvent> timeline,
      List<Finding> findings,
      List<CorrelatedEvent> correlated) {
    Optional<TimelineEvent> oom = events(timeline, TimelineEventType.OOM).stream().findFirst();
    if (oom.isEmpty()) {
      return;
    }
    List<TimelineEvent> fullGcs = collapseNearDuplicateObservations(events(timeline, TimelineEventType.FULL_GC).stream()
        .filter(fullGc -> !fullGc.timestamp().isAfter(oom.get().timestamp()))
        .filter(fullGc -> Duration.between(fullGc.timestamp(), oom.get().timestamp()).compareTo(Duration.ofMinutes(5)) <= 0)
        .toList());
    if (fullGcs.isEmpty()) {
      return;
    }
    TimelineEvent latest = fullGcs.getLast();
    long millisBeforeOom = Duration.between(latest.timestamp(), oom.get().timestamp()).toMillis();
    String explanation = fullGcs.size() + " Full GC attempt(s) occurred within 5 minutes before the OOM; "
        + "the last completed " + millisBeforeOom + "ms before it.";
    correlated.add(new CorrelatedEvent("FullGC_OOM_Correlation", latest, oom.get(), 0.88, explanation));
    findings.add(finding(
        "correlation-full-gc-oom-" + findings.size(),
        FindingSeverity.CRITICAL,
        FindingCategory.MEMORY,
        fullGcs.size() > 1 ? "OOM followed repeated Full GCs" : "OOM followed a Full GC",
        explanation,
        List.of(latest, oom.get()),
        "Treat this as memory exhaustion until heap evidence proves otherwise.",
        RemediationType.CONFIG_CHANGE,
        mapOf("rule", "FullGC_OOM_Correlation", "fullGcCount", fullGcs.size())));
  }

  private static List<TimelineEvent> collapseNearDuplicateObservations(List<TimelineEvent> events) {
    List<TimelineEvent> result = new ArrayList<>();
    for (TimelineEvent event : events) {
      if (result.isEmpty()
          || Duration.between(result.getLast().timestamp(), event.timestamp()).abs().toMillis() > 5) {
        result.add(event);
      }
    }
    return result;
  }

  private static void applyThreadPoolGcPressure(
      List<TimelineEvent> timeline,
      List<Finding> findings,
      List<CorrelatedEvent> correlated) {
    for (TimelineEvent pool : events(timeline, TimelineEventType.THREAD_POOL_EXHAUSTED)) {
      Optional<TimelineEvent> gc = timeline.stream()
          .filter(event -> event.type() == TimelineEventType.GC_PAUSE || event.type() == TimelineEventType.FULL_GC)
          .filter(event -> Math.abs(Duration.between(pool.timestamp(), event.timestamp()).toMillis()) <= 1_000)
          .findFirst();
      if (gc.isEmpty()) {
        continue;
      }
      String explanation = "Thread pool exhaustion and GC pressure occurred in the same 1-second window.";
      correlated.add(new CorrelatedEvent("ThreadPoolExhaustion_GCPressure_Correlation", pool, gc.get(), 0.78, explanation));
      findings.add(finding(
          "correlation-pool-gc-" + findings.size(),
          FindingSeverity.HIGH,
          FindingCategory.THREADING,
          "Thread pool exhaustion coincides with GC pressure",
          explanation,
          List.of(pool, gc.get()),
          "Check whether blocked worker threads are retaining allocation-heavy work.",
          RemediationType.ARCHITECTURAL,
          mapOf("rule", "ThreadPoolExhaustion_GCPressure_Correlation")));
    }
  }

  private static void applyDeoptCpuSpike(
      List<TimelineEvent> timeline,
      List<Finding> findings,
      List<CorrelatedEvent> correlated) {
    for (TimelineEvent deopt : events(timeline, TimelineEventType.DEOPT)) {
      Optional<TimelineEvent> cpu = firstWithin(timeline, TimelineEventType.CPU_SPIKE, deopt.timestamp(), Duration.ofMillis(100));
      if (cpu.isEmpty()) {
        continue;
      }
      String method = attr(deopt, "method");
      String explanation = "A deoptimization event" + (method.isBlank() ? "" : " in " + method)
          + " was followed by a CPU spike within 100ms.";
      correlated.add(new CorrelatedEvent("Deoptimization_CPUSpike_Correlation", deopt, cpu.get(), 0.76, explanation));
      findings.add(finding(
          "correlation-deopt-cpu-" + findings.size(),
          FindingSeverity.MEDIUM,
          FindingCategory.JIT,
          "CPU spike follows deoptimization",
          explanation,
          List.of(deopt, cpu.get()),
          "Inspect repeated deopt methods before tuning compiler flags.",
          RemediationType.CODE_CHANGE,
          mapOf("rule", "Deoptimization_CPUSpike_Correlation")));
    }
  }

  private static void applyRepeatedSystemGc(
      List<TimelineEvent> timeline,
      List<Finding> findings,
      List<CorrelatedEvent> correlated) {
    List<TimelineEvent> systemGc = events(timeline, TimelineEventType.SYSTEM_GC);
    if (systemGc.size() < 2) {
      return;
    }
    String explanation = systemGc.size()
        + " explicit System.gc() events were observed; this often comes from JMX operations or Spring Boot Actuator heap endpoints.";
    correlated.add(new CorrelatedEvent("RepeatedSystemGC_FrameworkTrigger_Correlation", systemGc.getFirst(), systemGc.getLast(), 0.72, explanation));
    findings.add(finding(
        "correlation-repeated-system-gc",
        FindingSeverity.HIGH,
        FindingCategory.GC,
        "Repeated System.gc() suggests external trigger",
        explanation,
        List.of(systemGc.getFirst(), systemGc.getLast()),
        "Audit JMX clients and Spring Boot Actuator heap/memory endpoints.",
        RemediationType.CONFIG_CHANGE,
        mapOf("rule", "RepeatedSystemGC_FrameworkTrigger_Correlation", "count", systemGc.size())));
  }

  private static void applyHumongousAllocation(
      List<TimelineEvent> timeline,
      List<Finding> findings,
      List<CorrelatedEvent> correlated) {
    List<TimelineEvent> humongous = events(timeline, TimelineEventType.HUMONGOUS_ALLOCATION);
    if (humongous.isEmpty()) {
      return;
    }
    TimelineEvent first = humongous.getFirst();
    TimelineEvent last = humongous.getLast();
    String explanation = humongous.size()
        + " humongous allocation event(s) indicate possible G1 region size or large-object allocation issues.";
    correlated.add(new CorrelatedEvent(
        "HumongousAllocation_RegionSizing_Correlation", first, last, 0.70, explanation));
    findings.add(finding(
        "correlation-humongous-region-" + findings.size(),
        FindingSeverity.MEDIUM,
        FindingCategory.GC,
        "Humongous allocation suggests region sizing review",
        explanation,
        List.of(first, last),
        "Confirm object sizes before changing -XX:G1HeapRegionSize.",
        RemediationType.JVM_FLAG,
        mapOf("rule", "HumongousAllocation_RegionSizing_Correlation", "count", humongous.size())));
  }

  private static void applyVirtualThreadPinningBlockingIo(
      List<TimelineEvent> timeline,
      List<Finding> findings,
      List<CorrelatedEvent> correlated) {
    for (TimelineEvent pinned : events(timeline, TimelineEventType.VIRTUAL_THREAD_PINNED)) {
      Optional<TimelineEvent> io = timeline.stream()
          .filter(event -> event.type() == TimelineEventType.BLOCKING_IO)
          .filter(event -> sameThread(pinned, event))
          .filter(event -> Math.abs(Duration.between(pinned.timestamp(), event.timestamp()).toMillis()) <= 1_000)
          .findFirst();
      if (io.isEmpty()) {
        continue;
      }
      String explanation = "A virtual thread pinning event correlates with blocking I/O on "
          + attr(io.get(), "io.target") + ".";
      correlated.add(new CorrelatedEvent("VirtualThreadPinning_BlockingIO_Correlation", pinned, io.get(), 0.86, explanation));
      findings.add(finding(
          "correlation-vthread-io-" + findings.size(),
          FindingSeverity.HIGH,
          FindingCategory.THREADING,
          "Virtual thread pinning correlates with blocking I/O",
          explanation,
          List.of(pinned, io.get()),
          "Move blocking I/O outside pinned synchronized/native regions.",
          RemediationType.CODE_CHANGE,
          mapOf("rule", "VirtualThreadPinning_BlockingIO_Correlation")));
    }
  }

  private static CorrelationSummary summary(
      List<CorrelatedEvent> correlated,
      List<TimelineEvent> timeline,
      List<String> bundledRuleNames,
      String engineName,
      String fallbackReason) {
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
    return new CorrelationSummary(
        narrative,
        correlated,
        metrics);
  }

  private static List<TimelineEvent> events(List<TimelineEvent> timeline, TimelineEventType type) {
    return timeline.stream().filter(event -> event.type() == type).toList();
  }

  private static Optional<TimelineEvent> firstWithin(
      List<TimelineEvent> timeline,
      TimelineEventType type,
      Instant start,
      Duration window) {
    return timeline.stream()
        .filter(event -> event.type() == type)
        .filter(event -> !event.timestamp().isBefore(start))
        .filter(event -> Duration.between(start, event.timestamp()).compareTo(window) <= 0)
        .findFirst();
  }

  private static boolean sameThread(TimelineEvent left, TimelineEvent right) {
    String leftThread = attr(left, "thread");
    String rightThread = attr(right, "thread");
    return !leftThread.isBlank() && leftThread.equals(rightThread);
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
