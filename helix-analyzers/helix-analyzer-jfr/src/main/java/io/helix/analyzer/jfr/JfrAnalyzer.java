package io.helix.analyzer.jfr;

import io.helix.core.model.ArtifactType;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingReliability;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.Remediation;
import io.helix.core.model.RemediationType;
import io.helix.core.model.jfr.FlameGraphNode;
import io.helix.core.model.jfr.JfrEvent;
import io.helix.core.model.jfr.JfrFrame;
import io.helix.core.model.jfr.JfrRecordingData;
import io.helix.core.spi.AnalysisContext;
import io.helix.core.spi.Analyzer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class JfrAnalyzer implements Analyzer {
  static final String JFR_DATA_KEY = "jfrRecording";
  private static final double SLOW_IO_THRESHOLD_MS = 100.0;

  @Override
  public List<Finding> analyze(ParsedArtifact artifact, AnalysisContext ctx) {
    Object data = artifact.data().get(JFR_DATA_KEY);
    if (!(data instanceof JfrRecordingData recording) || recording.events().isEmpty()) {
      return List.of();
    }

    List<Finding> findings = new ArrayList<>();
    addOutOfMemoryFinding(artifact, recording.events(), findings);
    addOldObjectRetentionFinding(artifact, recording.events(), findings);
    addFlameGraphFinding(artifact, recording.events(), findings);
    addAllocationHotspotFinding(artifact, recording.events(), findings);
    addLockContentionFinding(artifact, recording.events(), findings);
    addIoLatencyFinding(artifact, recording.events(), findings);
    addDeoptimizationFindings(artifact, recording.events(), findings);
    addVirtualThreadPinningFinding(artifact, recording.events(), findings);
    return findings;
  }

  @Override
  public Set<ArtifactType> supportedTypes() {
    return Set.of(ArtifactType.JFR);
  }

  @Override
  public String analyzerId() {
    return "helix-analyzer-jfr";
  }

  private static void addOutOfMemoryFinding(
      ParsedArtifact artifact,
      List<JfrEvent> events,
      List<Finding> findings) {
    List<JfrEvent> oomEvents = events.stream()
        .filter(event -> (event.eventType().equals("jdk.HeapDump")
                && Boolean.parseBoolean(String.valueOf(
                    event.attributes().getOrDefault("onOutOfMemoryError", false))))
            || (event.eventType().equals("jdk.JavaErrorThrow")
                && event.className().contains("OutOfMemoryError")))
        .toList();
    if (oomEvents.isEmpty()) {
      return;
    }
    JfrEvent primary = oomEvents.getFirst();
    findings.add(new Finding(
        artifact.artifact().id() + "-jfr-out-of-memory",
        FindingSeverity.CRITICAL,
        FindingCategory.MEMORY,
        "JFR recorded an OutOfMemoryError",
        primary.eventType().equals("jdk.HeapDump")
            ? "JFR recorded an OutOfMemoryError-triggered heap dump on thread "
                + primary.threadName() + " at " + primary.topFrame() + "."
            : "JFR recorded java.lang.OutOfMemoryError on thread "
                + primary.threadName() + " at " + primary.topFrame() + ".",
        0.98,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Correlate this event with retained-object samples, heap dominators, and GC reclamation.",
            RemediationType.CODE_CHANGE,
            "Identify the allocation and ownership path before changing heap sizing.",
            "This event establishes that an OOM occurred; the related evidence identifies why.")),
        mapOf("events", oomEvents.stream().map(JfrAnalyzer::eventSummary).toList()))
        .withReliability(FindingReliability.FACT));
  }

  private static void addOldObjectRetentionFinding(
      ParsedArtifact artifact,
      List<JfrEvent> events,
      List<Finding> findings) {
    Map<String, List<JfrEvent>> groups = events.stream()
        .filter(event -> event.eventType().equals("jdk.OldObjectSample"))
        .filter(event -> event.bytes() >= 1024L * 1024L)
        .collect(Collectors.groupingBy(
            event -> event.className() + " at " + allocationFrame(event),
            LinkedHashMap::new,
            Collectors.toList()));
    if (groups.isEmpty()) {
      return;
    }
    Map.Entry<String, List<JfrEvent>> top = groups.entrySet().stream()
        .max(Comparator.comparingLong(entry -> entry.getValue().stream()
            .mapToLong(JfrEvent::bytes)
            .sum()))
        .orElseThrow();
    long sampledBytes = top.getValue().stream().mapToLong(JfrEvent::bytes).sum();
    JfrEvent representative = top.getValue().getFirst();
    findings.add(new Finding(
        artifact.artifact().id() + "-jfr-old-object-retention",
        sampledBytes >= 50L * 1024L * 1024L ? FindingSeverity.HIGH : FindingSeverity.MEDIUM,
        FindingCategory.MEMORY,
        "Large surviving objects sampled by JFR",
        "JFR sampled " + top.getValue().size() + " surviving " + representative.className()
            + " objects allocated at " + allocationFrame(representative) + ", totaling "
            + format(mb(sampledBytes)) + " MB of sampled object size.",
        0.90,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Inspect the sampled allocation stack and the collection retaining these objects.",
            RemediationType.CODE_CHANGE,
            "Bound or release the retained objects at the ownership site.",
            "Old-object samples connect surviving objects to the code that allocated them.")),
        mapOf(
            "measurement", "JFR_OLD_OBJECT_SAMPLE",
            "sampleCount", top.getValue().size(),
            "sampledObjectBytes", sampledBytes,
            "classAndAllocationSite", top.getKey(),
            "events", top.getValue().stream().map(JfrAnalyzer::eventSummary).toList()))
        .withReliability(FindingReliability.DERIVED));
  }

  private static void addFlameGraphFinding(
      ParsedArtifact artifact,
      List<JfrEvent> events,
      List<Finding> findings) {
    List<JfrEvent> samples = events.stream()
        .filter(event -> event.eventType().equals("jdk.ExecutionSample"))
        .filter(event -> !event.stackTrace().isEmpty())
        .toList();
    if (samples.isEmpty()) {
      return;
    }
    FlameGraphNode cpuGraph = flameGraph(samples, "cpu");
    findings.add(new Finding(
        artifact.artifact().id() + "-jfr-cpu-flamegraph",
        FindingSeverity.INFO,
        FindingCategory.JIT,
        "JFR CPU flame graph",
        "Built a CPU flame graph from " + samples.size() + " execution sample event(s).",
        0.80,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Use the flame graph to identify hot stacks before changing JVM flags.",
            RemediationType.CODE_CHANGE,
            "",
            "Execution samples provide call tree shape; source-level fixes usually beat blind tuning.")),
        mapOf("mode", "cpu", "flameGraph", cpuGraph)));
  }

  private static void addAllocationHotspotFinding(
      ParsedArtifact artifact,
      List<JfrEvent> events,
      List<Finding> findings) {
    List<JfrEvent> sampledAllocations = events.stream()
        .filter(event -> event.eventType().equals("jdk.ObjectAllocationSample"))
        .filter(event -> event.bytes() > 0)
        .toList();
    List<JfrEvent> exactAllocations = events.stream()
        .filter(event -> event.eventType().equals("jdk.ObjectAllocationInNewTLAB")
            || event.eventType().equals("jdk.ObjectAllocationOutsideTLAB"))
        .filter(event -> event.bytes() > 0)
        .toList();
    boolean sampled = !sampledAllocations.isEmpty();
    List<JfrEvent> allocations = sampled ? sampledAllocations : exactAllocations;
    if (allocations.isEmpty()) {
      return;
    }
    Map<String, Long> byFrame = new LinkedHashMap<>();
    for (JfrEvent event : allocations) {
      String frame = allocationFrame(event);
      byFrame.merge(frame, event.bytes(), Long::sum);
    }
    List<AllocationHotspot> hotspots = byFrame.entrySet().stream()
        .map(entry -> new AllocationHotspot(entry.getKey(), entry.getValue(), allocationRateMbPerSecond(allocations, entry.getValue())))
        .sorted(Comparator.comparing(AllocationHotspot::bytes).reversed())
        .limit(10)
        .toList();
    AllocationHotspot top = hotspots.getFirst();
    findings.add(new Finding(
        artifact.artifact().id() + "-jfr-allocation-hotspots",
        top.bytes() >= 100 * 1024 * 1024 ? FindingSeverity.MEDIUM : FindingSeverity.INFO,
        FindingCategory.MEMORY,
        "JFR allocation hotspots",
        sampled
            ? "Top sampled allocation site is " + top.frame() + " with " + format(mb(top.bytes()))
                + " MB estimated allocation weight across " + allocations.size() + " sample event(s)."
            : "Top allocation site is " + top.frame() + " with " + format(mb(top.bytes())) + " MB allocated.",
        sampled ? 0.78 : 0.84,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Inspect the top allocation sites for avoidable object churn.",
            RemediationType.CODE_CHANGE,
            "Reduce short-lived allocations or reuse buffers where ownership is clear.",
            "JFR allocation events connect allocation pressure to code locations.")),
        mapOf(
            "measurement", sampled ? "SAMPLED_WEIGHT" : "EXACT_BYTES",
            "eventCount", allocations.size(),
            "hotspots", hotspots,
            "allocationFlameGraph", flameGraph(allocations, "allocation"))));
  }

  private static void addLockContentionFinding(
      ParsedArtifact artifact,
      List<JfrEvent> events,
      List<Finding> findings) {
    List<JfrEvent> waits = events.stream()
        .filter(event -> event.eventType().equals("jdk.JavaMonitorEnter"))
        .filter(event -> !event.duration().isZero())
        .toList();
    if (waits.isEmpty()) {
      return;
    }
    Map<String, List<JfrEvent>> byLock = waits.stream()
        .collect(Collectors.groupingBy(
            event -> event.lockName().isBlank() ? event.className() : event.lockName(),
            LinkedHashMap::new,
            Collectors.toList()));
    List<LockWait> locks = byLock.entrySet().stream()
        .map(entry -> new LockWait(
            entry.getKey(),
            entry.getValue().size(),
            entry.getValue().stream().mapToLong(event -> event.duration().toNanos()).sum(),
            entry.getValue().stream().map(JfrEvent::threadName).distinct().toList()))
        .sorted(Comparator.comparing(LockWait::totalWaitNanos).reversed())
        .toList();
    LockWait top = locks.getFirst();
    findings.add(new Finding(
        artifact.artifact().id() + "-jfr-lock-contention",
        top.totalWaitMillis() >= 1_000.0 ? FindingSeverity.HIGH : FindingSeverity.MEDIUM,
        FindingCategory.THREADING,
        "JFR lock contention profile",
        "Top contended lock is " + top.lockName() + " with " + format(top.totalWaitMillis())
            + "ms total wait across " + top.events() + " event(s).",
        0.86,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Reduce work inside the contended monitor or partition the lock.",
            RemediationType.CODE_CHANGE,
            "",
            "Monitor wait duration in JFR is stronger evidence than a single thread-dump sample.")),
        mapOf("locks", locks, "lockFlameGraph", flameGraph(waits, "lock"))));
  }

  private static void addIoLatencyFinding(
      ParsedArtifact artifact,
      List<JfrEvent> events,
      List<Finding> findings) {
    List<JfrEvent> slow = events.stream()
        .filter(event -> event.eventType().equals("jdk.FileRead")
            || event.eventType().equals("jdk.FileWrite")
            || event.eventType().equals("jdk.SocketRead")
            || event.eventType().equals("jdk.SocketWrite"))
        .filter(event -> event.durationMillis() > SLOW_IO_THRESHOLD_MS)
        .sorted(Comparator.comparing(JfrEvent::duration).reversed())
        .toList();
    if (slow.isEmpty()) {
      return;
    }
    JfrEvent top = slow.getFirst();
    FindingSeverity severity = criticalThread(top.threadName()) ? FindingSeverity.HIGH : FindingSeverity.MEDIUM;
    findings.add(new Finding(
        artifact.artifact().id() + "-jfr-slow-io",
        severity,
        FindingCategory.THREADING,
        "Slow JFR I/O operation",
        top.eventType() + " on " + top.ioTarget() + " took " + format(top.durationMillis())
            + "ms on thread " + top.threadName() + ".",
        0.82,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Move blocking I/O off critical executor threads or add timeouts/backpressure.",
            RemediationType.ARCHITECTURAL,
            "",
            "Long file or socket events in JFR explain stalls that may not appear as CPU hotspots.")),
        mapOf("events", slow.stream().map(JfrAnalyzer::eventSummary).toList(), "wallClockFlameGraph", flameGraph(slow, "wall-clock"))));
  }

  private static void addDeoptimizationFindings(
      ParsedArtifact artifact,
      List<JfrEvent> events,
      List<Finding> findings) {
    List<JfrEvent> deopts = events.stream()
        .filter(event -> event.eventType().equals("jdk.Deoptimization"))
        .toList();
    if (deopts.isEmpty()) {
      return;
    }
    Map<String, Long> byReason = deopts.stream()
        .collect(Collectors.groupingBy(
            event -> event.reason().isBlank() ? "Unknown" : event.reason(),
            LinkedHashMap::new,
            Collectors.counting()));
    findings.add(new Finding(
        artifact.artifact().id() + "-jfr-deoptimization-summary",
        FindingSeverity.INFO,
        FindingCategory.JIT,
        "JFR deoptimization summary",
        "Observed " + deopts.size() + " deoptimization event(s). Top reason: "
            + byReason.entrySet().stream().findFirst().map(Map.Entry::getKey).orElse("Unknown") + ".",
        0.78,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Use repeated deoptimization methods to guide JIT-sensitive code review.",
            RemediationType.CODE_CHANGE,
            "",
            "A few deopts are normal; repeated deopts in one method can explain CPU regressions.")),
        mapOf("reasons", byReason, "events", deopts.stream().map(JfrAnalyzer::eventSummary).toList())));

  }

  private static void addVirtualThreadPinningFinding(
      ParsedArtifact artifact,
      List<JfrEvent> events,
      List<Finding> findings) {
    List<JfrEvent> pinned = events.stream()
        .filter(event -> event.eventType().equals("jdk.VirtualThreadPinned"))
        .toList();
    if (pinned.isEmpty()) {
      return;
    }
    JfrEvent top = pinned.stream()
        .max(Comparator.comparing(JfrEvent::duration))
        .orElse(pinned.getFirst());
    findings.add(new Finding(
        artifact.artifact().id() + "-jfr-virtual-thread-pinning",
        FindingSeverity.HIGH,
        FindingCategory.THREADING,
        "Virtual thread pinning detected",
        pinned.size() + " virtual thread pinning event(s) were observed. Offending frame: " + top.topFrame() + ".",
        0.90,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Remove blocking synchronized/native sections from virtual-thread execution paths.",
            RemediationType.CODE_CHANGE,
            "",
            "Pinned virtual threads cannot yield their carrier, which defeats virtual-thread scalability.")),
        mapOf("events", pinned.stream().map(JfrAnalyzer::eventSummary).toList())));
  }

  private static FlameGraphNode flameGraph(List<JfrEvent> events, String mode) {
    MutableNode root = new MutableNode("root");
    for (JfrEvent event : events) {
      long bytes = mode.equals("allocation") ? event.bytes() : 0L;
      long durationNanos = mode.equals("lock") || mode.equals("wall-clock") ? event.duration().toNanos() : 0L;
      root.add(event.stackTrace(), bytes, durationNanos);
    }
    return root.toNode();
  }

  private static String allocationFrame(JfrEvent event) {
    if (!event.topFrame().isBlank()) {
      return event.topFrame();
    }
    return event.className().isBlank() ? "<unknown allocation site>" : event.className();
  }

  private static double allocationRateMbPerSecond(List<JfrEvent> allocations, long bytes) {
    if (allocations.size() < 2) {
      return 0.0;
    }
    Duration window = Duration.between(allocations.getFirst().startTime(), allocations.getLast().startTime());
    double seconds = Math.max(1.0, window.toMillis() / 1000.0);
    return mb(bytes) / seconds;
  }

  private static boolean criticalThread(String threadName) {
    String lower = threadName == null ? "" : threadName.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("main")
        || lower.contains("http")
        || lower.contains("executor")
        || lower.contains("forkjoin")
        || lower.contains("carrier");
  }

  private static Map<String, Object> eventSummary(JfrEvent event) {
    return mapOf(
        "eventType", event.eventType(),
        "timestamp", event.startTime().toString(),
        "durationMs", event.durationMillis(),
        "thread", event.threadName(),
        "topFrame", event.topFrame(),
        "bytes", event.bytes(),
        "className", event.className(),
        "methodName", event.methodName(),
        "lockName", event.lockName(),
        "ioTarget", event.ioTarget(),
        "reason", event.reason(),
        "attributes", event.attributes());
  }

  private static double mb(long bytes) {
    return bytes / (1024.0 * 1024.0);
  }

  private static String format(double value) {
    return String.format(java.util.Locale.ROOT, "%.2f", value);
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

  public record AllocationHotspot(String frame, long bytes, double allocationRateMbPerSecond) {}

  public record LockWait(String lockName, int events, long totalWaitNanos, List<String> threads) {
    public double totalWaitMillis() {
      return totalWaitNanos / 1_000_000.0;
    }
  }

  private static final class MutableNode {
    private final String name;
    private long samples;
    private long bytes;
    private long durationNanos;
    private final Map<String, MutableNode> children = new LinkedHashMap<>();

    private MutableNode(String name) {
      this.name = name;
    }

    private void add(List<JfrFrame> frames, long bytes, long durationNanos) {
      samples++;
      this.bytes += bytes;
      this.durationNanos += durationNanos;
      MutableNode current = this;
      for (int i = frames.size() - 1; i >= 0; i--) {
        String frameName = frames.get(i).displayName();
        current = current.children.computeIfAbsent(frameName, MutableNode::new);
        current.samples++;
        current.bytes += bytes;
        current.durationNanos += durationNanos;
      }
    }

    private FlameGraphNode toNode() {
      return new FlameGraphNode(
          name,
          samples,
          bytes,
          durationNanos,
          children.values().stream()
              .sorted(Comparator.comparing((MutableNode node) -> node.samples).reversed())
              .map(MutableNode::toNode)
              .toList());
    }
  }
}
