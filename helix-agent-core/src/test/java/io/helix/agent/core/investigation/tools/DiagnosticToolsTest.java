package io.helix.agent.core.investigation.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.JvmProfile;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.StackFrame;
import io.helix.core.model.gc.GcCollector;
import io.helix.core.model.gc.GcEvent;
import io.helix.core.model.gc.GcLogData;
import io.helix.core.model.gc.GcLogFormat;
import io.helix.core.model.heap.ByteArrayLeakSuspect;
import io.helix.core.model.heap.DuplicateStringSummary;
import io.helix.core.model.heap.FinalizerQueueSummary;
import io.helix.core.model.heap.HeapDominatorEntry;
import io.helix.core.model.heap.HeapLeakSuspect;
import io.helix.core.model.heap.HeapSnapshotData;
import io.helix.core.model.jfr.JfrEvent;
import io.helix.core.model.jfr.JfrFrame;
import io.helix.core.model.jfr.JfrRecordingData;
import io.helix.core.model.thread.JavaThreadState;
import io.helix.core.model.thread.ThreadDumpData;
import io.helix.core.model.thread.ThreadDumpFormat;
import io.helix.core.model.thread.ThreadDumpSnapshot;
import io.helix.core.model.thread.ThreadLock;
import io.helix.core.model.thread.ThreadSample;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiagnosticToolsTest {
  private static final Instant CAPTURED_AT = Instant.parse("2026-01-01T10:30:00Z");

  @Test
  void resolvesLockOwnerFromNormalizedThreadDump() {
    ThreadLock lock = new ThreadLock("0x123", "java.lang.Object", "", "");
    ThreadSample owner = thread("owner", JavaThreadState.RUNNABLE, List.of(lock), null);
    ThreadSample waiter = thread("waiter", JavaThreadState.BLOCKED, List.of(), lock);
    ThreadDumpData data = new ThreadDumpData(
        List.of(new ThreadDumpSnapshot(
            CAPTURED_AT,
            ThreadDumpFormat.HOTSPOT,
            List.of(owner, waiter),
            Map.of())),
        ThreadDumpFormat.HOTSPOT,
        Map.of());

    var result = new QueryThreadsTool().execute(
        new QueryThreadsTool.Input("LOCK_GRAPH", "", "", "", "", 20),
        context(parsed(ArtifactType.THREAD_DUMP, "threads", data)));

    assertThat(result.summary()).contains("1 lock-wait edge");
    assertThat(result.data().toString())
        .contains("waitingThread=waiter")
        .contains("ownerThread=owner")
        .contains("lockId=0x123");
  }

  @Test
  void aggregatesFilteredJfrEventsByMethod() {
    JfrEvent first = event("jdk.ObjectAllocationSample", "checkout", "allocate", 1024);
    JfrEvent second = event("jdk.ObjectAllocationSample", "checkout", "allocate", 2048);
    JfrEvent unrelated = event("jdk.SocketRead", "network", "read", 0);
    JfrRecordingData data = new JfrRecordingData(List.of(first, second, unrelated), Map.of());

    var result = new QueryJfrTool().execute(
        new QueryJfrTool.Input(
            "jdk.ObjectAllocationSample", "", "", "checkout", "", "", "", "TOP_METHODS", 10),
        context(parsed(ArtifactType.JFR, "recording", data)));

    assertThat(result.summary()).contains("2 JFR event");
    assertThat(result.data().toString())
        .contains("com.example.Service.allocate")
        .contains("bytes=3072")
        .contains("count=2");
  }

  @Test
  void summarizesGcExhaustionSignals() {
    GcEvent fullGc = new GcEvent(
        "gc-13",
        CAPTURED_AT,
        Duration.ofMillis(750),
        Duration.ofMillis(11),
        "Pause Full",
        GcCollector.G1,
        "G1 Humongous Allocation",
        true,
        false,
        false,
        true,
        58.0,
        58.0,
        64.0,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of());
    GcLogData data = new GcLogData(List.of(fullGc), GcLogFormat.UNIFIED, Map.of());

    var result = new QueryGcTool().execute(
        new QueryGcTool.Input("SUMMARY", "", "", "", "G1", "ANY", 0, 20),
        context(parsed(ArtifactType.GC_LOG, "gc", data)));

    assertThat(result.data())
        .containsEntry("eventCount", 1)
        .containsEntry("fullGcCount", 1L)
        .containsEntry("humongousAllocationCount", 1L)
        .containsEntry("latestHeapAfterMb", 58.0)
        .containsEntry("latestHeapCommittedMb", 64.0);
  }

  @Test
  void returnsCompactHeapRetentionEvidence() {
    HeapSnapshotData heap = new HeapSnapshotData(
        56_000_000,
        75_000,
        1_900,
        10,
        List.of(),
        List.of(new HeapDominatorEntry(
            "main Thread",
            "java.lang.Thread",
            "<bootstrap>",
            1,
            52_000_000,
            92.8,
            List.of("[GCRoot]", "main Thread"))),
        List.of(new HeapLeakSuspect(
            "java.lang.Thread",
            "<bootstrap>",
            52_000_000,
            92.8,
            List.of("[GCRoot]", "main Thread"))),
        DuplicateStringSummary.empty(),
        FinalizerQueueSummary.empty(),
        List.of(new ByteArrayLeakSuspect(
            "byte[]",
            17_000,
            53_000_000,
            94.6,
            List.of("[GCRoot]", "main Thread", "byte[]"))),
        List.of(),
        Map.of("mat.internal", "must not be returned"));

    var result = new QueryHeapTool().execute(
        new QueryHeapTool.Input("BYTE_ARRAYS", "", 10),
        context(parsed(ArtifactType.HEAP_DUMP, "heap", heap)));

    assertThat(result.data().toString())
        .contains("totalBytes=53000000")
        .contains("heapPercent=94.6")
        .contains("main Thread")
        .doesNotContain("mat.internal");
  }

  private static ThreadSample thread(
      String name,
      JavaThreadState state,
      List<ThreadLock> held,
      ThreadLock waiting) {
    return new ThreadSample(
        name,
        "",
        "",
        state,
        List.of(new StackFrame("com.example.Worker", "run", "Worker.java", 42, "", false)),
        held,
        waiting,
        null,
        0,
        "");
  }

  private static JfrEvent event(String type, String thread, String method, long bytes) {
    return new JfrEvent(
        type,
        CAPTURED_AT,
        Duration.ofMillis(5),
        thread,
        List.of(new JfrFrame("com.example.Service", method, 10)),
        bytes,
        "com.example.Service",
        method,
        "",
        "",
        "",
        Map.of());
  }

  private static ParsedArtifact parsed(ArtifactType type, String id, Object data) {
    return new ParsedArtifact(
        new Artifact(id, type, CAPTURED_AT, Map.of(), JvmFlavour.HOTSPOT, Path.of(id)),
        List.of(),
        Map.of("normalized", data),
        List.of());
  }

  private static InvestigationContext context(ParsedArtifact parsed) {
    DiagnosticReport report = new DiagnosticReport(
        "report-1",
        CAPTURED_AT,
        List.of(parsed.artifact()),
        List.of(),
        List.of(),
        CorrelationSummary.empty(),
        JvmProfile.unknown(),
        null);
    return new InvestigationContext("What happened?", report, List.of(parsed));
  }
}
