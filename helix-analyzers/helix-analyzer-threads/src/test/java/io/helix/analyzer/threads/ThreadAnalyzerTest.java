package io.helix.analyzer.threads;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingReliability;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.StackFrame;
import io.helix.core.model.thread.JavaThreadState;
import io.helix.core.model.thread.ThreadDumpData;
import io.helix.core.model.thread.ThreadDumpFormat;
import io.helix.core.model.thread.ThreadDumpSnapshot;
import io.helix.core.model.thread.ThreadLock;
import io.helix.core.model.thread.ThreadSample;
import io.helix.core.spi.AnalysisContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThreadAnalyzerTest {
  @Test
  void producesPhaseThreeThreadFindings() {
    ParsedArtifact artifact = parsedArtifact(new ThreadDumpData(
        List.of(new ThreadDumpSnapshot(
            Instant.parse("2026-01-01T00:00:00Z"),
            ThreadDumpFormat.HOTSPOT,
            phaseThreeThreads(),
            Map.of())),
        ThreadDumpFormat.HOTSPOT,
        Map.of()));

    List<Finding> findings = new ThreadAnalyzer().analyze(artifact, AnalysisContext.defaults());

    assertThat(findings)
        .extracting(Finding::title)
        .contains(
            "Java thread deadlock detected",
            "Lock contention ranking",
            "Thread state distribution",
            "Duplicate thread stacks",
            "Thread pool exhaustion: Spark executor task launch workers");
    assertThat(findings)
        .filteredOn(finding -> finding.title().equals("Java thread deadlock detected"))
        .singleElement()
        .satisfies(finding -> {
          assertThat(finding.severity()).isEqualTo(FindingSeverity.CRITICAL);
          assertThat(finding.reliability()).isEqualTo(FindingReliability.FACT);
          assertThat(finding.explanation())
              .contains("Deadlock-1 waits for")
              .contains("held by Deadlock-2")
              .contains("Deadlock-2 waits for")
              .contains("held by Deadlock-1");
        });
    assertThat(findings)
        .filteredOn(finding -> finding.title().startsWith("Thread pool exhaustion"))
        .singleElement()
        .extracting(Finding::reliability)
        .isEqualTo(FindingReliability.HEURISTIC);
  }

  @Test
  void detectsThreadsStuckAcrossSnapshots() {
    ThreadSample stuck = thread(
        "stuck-worker",
        JavaThreadState.BLOCKED,
        List.of(frame("com.example.Progression", "waitForever", 11), frame("com.example.Progression", "run", 7)),
        List.of(),
        lock("0x3000", "java.lang.Object"),
        0);
    ParsedArtifact artifact = parsedArtifact(new ThreadDumpData(
        List.of(
            new ThreadDumpSnapshot(Instant.parse("2026-01-01T00:00:00Z"), ThreadDumpFormat.HOTSPOT, List.of(stuck), Map.of()),
            new ThreadDumpSnapshot(Instant.parse("2026-01-01T00:00:10Z"), ThreadDumpFormat.HOTSPOT, List.of(stuck), Map.of())),
        ThreadDumpFormat.HOTSPOT,
        Map.of()));

    List<Finding> findings = new ThreadAnalyzer().analyze(artifact, AnalysisContext.defaults());

    assertThat(findings)
        .extracting(Finding::title)
        .contains("Threads stuck across snapshots");
  }

  @Test
  void detectsCpuHotspotFromCpuGrowthAcrossTimestampedSnapshots() {
    ThreadSample before = thread(
        "cpu-worker",
        JavaThreadState.RUNNABLE,
        List.of(frame("com.example.Cpu", "spin", 10)),
        List.of(),
        null,
        100);
    ThreadSample after = thread(
        "cpu-worker",
        JavaThreadState.RUNNABLE,
        List.of(frame("com.example.Cpu", "spin", 10)),
        List.of(),
        null,
        8_100);
    ParsedArtifact artifact = parsedArtifact(new ThreadDumpData(
        List.of(
            new ThreadDumpSnapshot(
                Instant.parse("2026-01-01T00:00:00Z"), ThreadDumpFormat.HOTSPOT, List.of(before), Map.of()),
            new ThreadDumpSnapshot(
                Instant.parse("2026-01-01T00:00:10Z"), ThreadDumpFormat.HOTSPOT, List.of(after), Map.of())),
        ThreadDumpFormat.HOTSPOT,
        Map.of()));

    List<Finding> findings = new ThreadAnalyzer().analyze(artifact, AnalysisContext.defaults());

    assertThat(findings)
        .filteredOn(finding -> finding.title().equals("CPU hotspot thread"))
        .singleElement()
        .satisfies(finding -> assertThat(finding.explanation())
            .contains("8000ms CPU over 10000ms")
            .contains("80.0% of one core"));
  }

  @Test
  void doesNotReportConditionWaitOrSingleAcquisitionWaiterAsContention() {
    ThreadLock condition = new ThreadLock("0x1000", "java.lang.ref.ReferenceQueue$Lock", "Finalizer", "waiting on");
    ThreadSample finalizer = new ThreadSample(
        "Finalizer",
        "",
        "",
        JavaThreadState.WAITING,
        List.of(frame("java.lang.Object", "wait", -1)),
        List.of(new ThreadLock("0x1000", "java.lang.ref.ReferenceQueue$Lock", "", "locked")),
        null,
        condition,
        0,
        "\"Finalizer\"");
    ThreadSample singleWaiter = thread(
        "single-waiter",
        JavaThreadState.BLOCKED,
        List.of(frame("com.example.Worker", "run", 12)),
        List.of(),
        lock("0x2000", "java.lang.Object"),
        0);
    ParsedArtifact artifact = parsedArtifact(new ThreadDumpData(
        List.of(new ThreadDumpSnapshot(
            Instant.parse("2026-01-01T00:00:00Z"),
            ThreadDumpFormat.HOTSPOT,
            List.of(finalizer, singleWaiter),
            Map.of())),
        ThreadDumpFormat.HOTSPOT,
        Map.of()));

    List<Finding> findings = new ThreadAnalyzer().analyze(artifact, AnalysisContext.defaults());

    assertThat(findings).extracting(Finding::title).doesNotContain("Lock contention ranking");
  }

  private static List<ThreadSample> phaseThreeThreads() {
    List<StackFrame> duplicateStack = List.of(
        frame("com.example.spark.ShuffleWriter", "write", 42),
        frame("com.example.spark.TaskRunner", "run", 31),
        frame("org.apache.spark.executor.Executor$TaskRunner", "run", 500),
        frame("java.util.concurrent.ThreadPoolExecutor", "runWorker", 1136));
    ThreadLock lockA = lock("0x1", "java.lang.Object");
    ThreadLock lockB = lock("0x2", "java.lang.Object");
    ThreadLock shared = lock("0x1000", "java.lang.Object");

    return List.of(
        thread("Deadlock-1", JavaThreadState.BLOCKED, List.of(frame("com.example.Deadlock", "first", 21)), List.of(lockB), lockA, 12),
        thread("Deadlock-2", JavaThreadState.BLOCKED, List.of(frame("com.example.Deadlock", "second", 31)), List.of(lockA), lockB, 14),
        thread("Executor task launch worker-1", JavaThreadState.BLOCKED, duplicateStack, List.of(), shared, 0),
        thread("Executor task launch worker-2", JavaThreadState.BLOCKED, duplicateStack, List.of(), shared, 0),
        thread("Executor task launch worker-3", JavaThreadState.BLOCKED, duplicateStack, List.of(), shared, 0),
        thread("Executor task launch worker-4", JavaThreadState.BLOCKED, duplicateStack, List.of(), shared, 0),
        thread("Executor task launch worker-5", JavaThreadState.RUNNABLE, duplicateStack, List.of(), null, 0),
        thread("shuffle-owner", JavaThreadState.RUNNABLE, List.of(frame("com.example.spark.ShuffleLockOwner", "flush", 12)), List.of(shared), null, 0),
        thread("cpu-hot", JavaThreadState.RUNNABLE, List.of(frame("com.example.Cpu", "spin", 10)), List.of(), null, 250));
  }

  private static ParsedArtifact parsedArtifact(ThreadDumpData data) {
    Artifact artifact = new Artifact(
        "thread-sample",
        ArtifactType.THREAD_DUMP,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of("filename", "threads.txt"),
        JvmFlavour.HOTSPOT,
        Path.of("/tmp/threads.txt"));
    return new ParsedArtifact(
        artifact,
        List.of(),
        Map.of(ThreadAnalyzer.THREAD_DATA_KEY, data),
        List.of());
  }

  private static ThreadSample thread(
      String name,
      JavaThreadState state,
      List<StackFrame> stack,
      List<ThreadLock> heldLocks,
      ThreadLock waitingToLock,
      long cpuTimeMillis) {
    return new ThreadSample(
        name,
        "",
        "",
        state,
        stack,
        heldLocks,
        waitingToLock,
        null,
        cpuTimeMillis,
        "\"" + name + "\"");
  }

  private static ThreadLock lock(String identity, String className) {
    return new ThreadLock(identity, className, "", "test");
  }

  private static StackFrame frame(String className, String methodName, int lineNumber) {
    return new StackFrame(className, methodName, className.substring(className.lastIndexOf('.') + 1) + ".java", lineNumber, "", false);
  }
}
