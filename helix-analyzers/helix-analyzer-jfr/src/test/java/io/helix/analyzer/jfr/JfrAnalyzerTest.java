package io.helix.analyzer.jfr;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingReliability;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.jfr.JfrEvent;
import io.helix.core.model.jfr.JfrFrame;
import io.helix.core.model.jfr.JfrRecordingData;
import io.helix.core.spi.AnalysisContext;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JfrAnalyzerTest {
  @Test
  void producesPhaseFiveJfrFindings() {
    List<Finding> findings = new JfrAnalyzer().analyze(
        parsedArtifact(new JfrRecordingData(events(), Map.of())),
        AnalysisContext.defaults());

    assertThat(findings)
        .extracting(Finding::title)
        .contains(
            "JFR CPU flame graph",
            "JFR allocation hotspots",
            "JFR lock contention profile",
            "Slow JFR I/O operation",
            "JFR deoptimization summary",
            "Repeated JFR deoptimizations",
            "Virtual thread pinning detected");
    assertThat(findings)
        .filteredOn(finding -> finding.title().equals("Virtual thread pinning detected"))
        .singleElement()
        .satisfies(finding -> assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH));
  }

  @Test
  void doesNotTreatIntentionalMonitorWaitAsContention() {
    JfrEvent monitorWait = event(
        "jdk.JavaMonitorWait",
        Instant.parse("2026-01-01T00:00:00Z"),
        Duration.ofMinutes(1),
        0,
        "java.lang.ref.ReferenceQueue$Lock",
        "",
        "java.lang.ref.ReferenceQueue$Lock",
        "");

    List<Finding> findings = new JfrAnalyzer().analyze(
        parsedArtifact(new JfrRecordingData(List.of(monitorWait), Map.of())),
        AnalysisContext.defaults());

    assertThat(findings)
        .extracting(Finding::title)
        .doesNotContain("JFR lock contention profile");
  }

  @Test
  void labelsSampledAllocationWeightAsEstimated() {
    JfrEvent sample = event(
        "jdk.ObjectAllocationSample",
        Instant.parse("2026-01-01T00:00:00Z"),
        Duration.ZERO,
        200L * 1024L * 1024L,
        "byte[]",
        "",
        "",
        "");

    List<Finding> findings = new JfrAnalyzer().analyze(
        parsedArtifact(new JfrRecordingData(List.of(sample), Map.of())),
        AnalysisContext.defaults());

    assertThat(findings)
        .filteredOn(finding -> finding.title().equals("JFR allocation hotspots"))
        .singleElement()
        .satisfies(finding -> {
          assertThat(finding.explanation()).contains("estimated allocation weight");
          assertThat(finding.rawData()).containsEntry("measurement", "SAMPLED_WEIGHT");
        });
  }

  @Test
  void connectsOomToSurvivingObjectsAndTheirAllocationSite() {
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    List<JfrEvent> events = new ArrayList<>();
    events.add(new JfrEvent(
        "jdk.HeapDump",
        base,
        Duration.ZERO,
        "main",
        List.of(new JfrFrame("JVMFaultSimulator", "triggerHeapOOM", 67)),
        0,
        "",
        "",
        "",
        "",
        "",
        Map.of("onOutOfMemoryError", true, "destination", "heap_dump_fault.hprof")));
    for (int i = 0; i < 5; i++) {
      events.add(new JfrEvent(
          "jdk.OldObjectSample",
          base.minusSeconds(5 - i),
          Duration.ZERO,
          "main",
          List.of(new JfrFrame("JVMFaultSimulator", "triggerHeapOOM", 67)),
          10L * 1024L * 1024L,
          "byte[]",
          "",
          "",
          "",
          "",
          Map.of()));
    }

    List<Finding> findings = new JfrAnalyzer().analyze(
        parsedArtifact(new JfrRecordingData(events, Map.of())),
        AnalysisContext.defaults());

    assertThat(findings)
        .filteredOn(finding -> finding.title().equals("JFR recorded an OutOfMemoryError"))
        .singleElement()
        .satisfies(finding -> {
          assertThat(finding.reliability()).isEqualTo(FindingReliability.FACT);
          assertThat(finding.explanation()).contains("JVMFaultSimulator.triggerHeapOOM:67");
        });
    assertThat(findings)
        .filteredOn(finding -> finding.title().equals("Large surviving objects sampled by JFR"))
        .singleElement()
        .satisfies(finding -> {
          assertThat(finding.reliability()).isEqualTo(FindingReliability.DERIVED);
          assertThat(finding.explanation())
              .contains("5 surviving byte[]")
              .contains("JVMFaultSimulator.triggerHeapOOM:67")
              .contains("50.00 MB");
        });
  }

  private static List<JfrEvent> events() {
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    List<JfrEvent> events = new ArrayList<>();
    events.add(event("jdk.ExecutionSample", base, Duration.ZERO, 0, "", "com.example.Cpu.hot", "", ""));
    events.add(event("jdk.ObjectAllocationOutsideTLAB", base.plusMillis(10), Duration.ZERO, 256L * 1024L * 1024L, "byte[]", "", "", ""));
    events.add(event("jdk.JavaMonitorEnter", base.plusMillis(20), Duration.ofMillis(1200), 0, "java.lang.Object", "", "java.lang.Object", ""));
    events.add(event("jdk.FileRead", base.plusMillis(30), Duration.ofMillis(250), 4096, "", "", "", "/tmp/slow.data"));
    for (int i = 0; i < 4; i++) {
      events.add(event("jdk.Deoptimization", base.plusMillis(40 + i), Duration.ZERO, 0, "", "com.example.Hot.method", "", ""));
    }
    events.add(event("jdk.VirtualThreadPinned", base.plusMillis(60), Duration.ofMillis(500), 0, "", "", "", ""));
    return events;
  }

  private static JfrEvent event(
      String type,
      Instant start,
      Duration duration,
      long bytes,
      String className,
      String methodName,
      String lockName,
      String ioTarget) {
    return new JfrEvent(
        type,
        start,
        duration,
        "http-executor-1",
        List.of(new JfrFrame("com.example.Service", "run", 42), new JfrFrame("com.example.App", "main", 11)),
        bytes,
        className,
        methodName,
        lockName,
        ioTarget,
        type.equals("jdk.Deoptimization") ? "unstable_if" : "",
        Map.of());
  }

  private static ParsedArtifact parsedArtifact(JfrRecordingData data) {
    Artifact artifact = new Artifact(
        "jfr-sample",
        ArtifactType.JFR,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of("filename", "profile.jfr"),
        JvmFlavour.HOTSPOT,
        Path.of("/tmp/profile.jfr"));
    return new ParsedArtifact(artifact, List.of(), Map.of(JfrAnalyzer.JFR_DATA_KEY, data), List.of());
  }
}
