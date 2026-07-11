package io.helix.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.Finding;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.TimelineEvent;
import io.helix.core.model.TimelineEventType;
import io.helix.core.model.heap.DuplicateStringSummary;
import io.helix.core.model.heap.FinalizerQueueSummary;
import io.helix.core.model.heap.HeapLeakSuspect;
import io.helix.core.model.heap.HeapSnapshotData;
import io.helix.core.spi.CorrelationResult;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleBasedCorrelatorTest {
  @Test
  void droolsRulesCompileAndRun() {
    CorrelationResult result = new DroolsCorrelationEngine()
        .correlate(List.of(), List.of(), DrlRuleCatalog.bundledRuleNames());

    assertThat(result.summary().metrics()).containsEntry("correlationEngine", "drools");
  }

  @Test
  void appliesAllMandatoryPhaseSevenRules() {
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    List<TimelineEvent> timeline = List.of(
        event(base, "gc", TimelineEventType.GC_PAUSE, Duration.ofMillis(900), Map.of("cause", "Allocation Failure")),
        event(base.plusMillis(500), "threads", TimelineEventType.THREAD_BLOCKED, Duration.ofMillis(200), Map.of("lock", "DataSourcePool")),
        event(base.plusSeconds(2), "jfr", TimelineEventType.ALLOCATION_PRESSURE, Duration.ZERO, Map.of("bytes", 104857600L)),
        event(base.plusSeconds(3), "gc", TimelineEventType.FULL_GC, Duration.ofSeconds(4), Map.of()),
        event(base.plusSeconds(4), "crash", TimelineEventType.OOM, null, Map.of()),
        event(base.plusSeconds(5), "threads", TimelineEventType.THREAD_POOL_EXHAUSTED, null, Map.of("pool", "http")),
        event(base.plusSeconds(5).plusMillis(100), "gc", TimelineEventType.GC_PAUSE, Duration.ofMillis(700), Map.of()),
        event(base.plusSeconds(6), "jfr", TimelineEventType.DEOPT, null, Map.of("method", "com.example.Hot.method")),
        event(base.plusSeconds(6).plusMillis(50), "jfr", TimelineEventType.CPU_SPIKE, null, Map.of("thread", "main")),
        event(base.plusSeconds(7), "gc", TimelineEventType.SYSTEM_GC, Duration.ofSeconds(1), Map.of()),
        event(base.plusSeconds(8), "gc", TimelineEventType.SYSTEM_GC, Duration.ofSeconds(1), Map.of()),
        event(base.plusSeconds(9), "gc", TimelineEventType.HUMONGOUS_ALLOCATION, Duration.ofMillis(50), Map.of()),
        event(base.plusSeconds(10), "jfr", TimelineEventType.VIRTUAL_THREAD_PINNED, Duration.ofMillis(500), Map.of("thread", "VirtualThread-1")),
        event(base.plusSeconds(10).plusMillis(100), "jfr", TimelineEventType.BLOCKING_IO, Duration.ofMillis(300), Map.of("thread", "VirtualThread-1", "io.target", "/tmp/pinned")));

    CorrelationResult result = new RuleBasedCorrelator().correlate(List.of(heapArtifact()), timeline);

    assertThat(result.findings())
        .extracting(Finding::title)
        .contains(
            "Thread contention spike follows GC pause",
            "Allocation pressure aligns with heap leak suspects",
            "OOM follows Full GC",
            "Thread pool exhaustion coincides with GC pressure",
            "CPU spike follows deoptimization",
            "Repeated System.gc() suggests external trigger",
            "Humongous allocation suggests region sizing review",
            "Virtual thread pinning correlates with blocking I/O");
    assertThat(result.summary().narrative()).contains("Phase 7 correlation rule");
    assertThat(result.summary().correlatedEvents()).hasSizeGreaterThanOrEqualTo(8);
    assertThat(result.summary().metrics().get("bundledDrlRules").toString())
        .contains("GCPressure_ThreadContention_Correlation")
        .contains("VirtualThreadPinning_BlockingIO_Correlation");
    assertThat(result.summary().metrics()).containsEntry("correlationEngine", "drools");
  }

  private static TimelineEvent event(
      Instant timestamp,
      String artifactId,
      TimelineEventType type,
      Duration duration,
      Map<String, Object> attributes) {
    return new TimelineEvent(timestamp, artifactId, type, duration, attributes);
  }

  private static ParsedArtifact heapArtifact() {
    Artifact artifact = new Artifact(
        "heap",
        ArtifactType.HEAP_DUMP,
        Instant.parse("2026-01-01T00:00:11Z"),
        Map.of("filename", "heap.hprof"),
        JvmFlavour.HOTSPOT,
        Path.of("/tmp/heap.hprof"));
    HeapSnapshotData heap = new HeapSnapshotData(
        1024,
        10,
        1,
        1,
        List.of(),
        List.of(),
        List.of(new HeapLeakSuspect("com.example.Leak", "app", 512, 50.0, List.of("GC Root", "Leak"))),
        DuplicateStringSummary.empty(),
        FinalizerQueueSummary.empty(),
        List.of(),
        List.of(),
        Map.of());
    return new ParsedArtifact(artifact, List.of(), Map.of("heapSnapshot", heap), List.of());
  }
}
