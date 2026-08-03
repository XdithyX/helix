package io.helix.analyzer.gc;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingReliability;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.gc.GcCollector;
import io.helix.core.model.gc.GcEvent;
import io.helix.core.model.gc.GcLogData;
import io.helix.core.model.gc.GcLogFormat;
import io.helix.core.spi.AnalysisContext;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GcAnalyzerTest {
  @Test
  void producesPhaseFourGcFindings() {
    List<Finding> findings = new GcAnalyzer().analyze(
        parsedArtifact(new GcLogData(phaseFourEvents(), GcLogFormat.UNIFIED, Map.of())),
        new AnalysisContext(null, Map.of("gc.pauseWarningMs", 500.0, "gc.throughputWarningPercent", 95.0)));

    assertThat(findings)
        .extracting(Finding::title)
        .contains(
            "GC pause statistics",
            "GC p99 pause exceeds threshold",
            "GC cause breakdown",
            "Explicit System.gc() detected",
            "Humongous allocation GC pattern",
            "Frequent metadata-triggered GC",
            "Allocation rate summary",
            "High promotion rate");
    assertThat(findings)
        .filteredOn(finding -> finding.title().equals("Explicit System.gc() detected"))
        .singleElement()
        .satisfies(finding -> {
          assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
          assertThat(finding.reliability()).isEqualTo(FindingReliability.FACT);
        });
    assertThat(findings)
        .extracting(Finding::title)
        .doesNotContain("Heap-after-GC leak gradient", "GC throughput below target");
  }

  @Test
  void calculatesThroughputOnlyAcrossARepresentativeWindow() {
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    List<GcEvent> events = List.of(
        event("gc-0", base, 0, 30_000, "G1 Evacuation Pause", false, false, 500, 300, 200, 250, 200),
        event("gc-1", base.plusSeconds(600), 600, 30_000, "G1 Evacuation Pause", false, false, 500, 300, 200, 250, 200));

    List<Finding> findings = new GcAnalyzer().analyze(
        parsedArtifact(new GcLogData(events, GcLogFormat.UNIFIED, Map.of())),
        new AnalysisContext(null, Map.of("gc.throughputWarningPercent", 95.0)));

    assertThat(findings)
        .filteredOn(finding -> finding.title().equals("GC throughput below target"))
        .singleElement()
        .satisfies(finding -> assertThat(finding.rawData().toString())
            .contains("windowMillis=600000.0"));
  }

  @Test
  void reportsLeakGradientOnlyAcrossARepresentativeWindow() {
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    List<GcEvent> events = List.of(
        event("gc-0", base, 0, 10, "G1 Evacuation Pause", false, false, 300, 200, 100, 120, 100),
        event("gc-1", base.plusSeconds(150), 150, 10, "G1 Evacuation Pause", false, false, 350, 225, 120, 140, 100),
        event("gc-2", base.plusSeconds(300), 300, 10, "G1 Evacuation Pause", false, false, 400, 250, 140, 160, 100),
        event("gc-3", base.plusSeconds(450), 450, 10, "G1 Evacuation Pause", false, false, 450, 275, 160, 180, 100),
        event("gc-4", base.plusSeconds(600), 600, 10, "G1 Evacuation Pause", false, false, 500, 300, 180, 200, 100));

    List<Finding> findings = new GcAnalyzer().analyze(
        parsedArtifact(new GcLogData(events, GcLogFormat.UNIFIED, Map.of())),
        new AnalysisContext(null, Map.of()));

    assertThat(findings)
        .filteredOn(finding -> finding.title().equals("Heap-after-GC leak gradient"))
        .singleElement()
        .satisfies(finding -> {
          assertThat(finding.reliability()).isEqualTo(FindingReliability.HEURISTIC);
          assertThat(finding.rawData().toString()).contains("windowMillis=600000.0");
        });
  }

  @Test
  void reportsWhenRepeatedFullGcsReclaimAlmostNothing() {
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    List<GcEvent> events = List.of(
        event("gc-12", base, 0, 7, "G1 Compaction Pause", true, false, 58, 58, 58, 58, 0),
        event("gc-13", base.plusMillis(10), 0, 11, "G1 Compaction Pause", true, false, 58, 58, 58, 58, 0));

    List<Finding> findings = new GcAnalyzer().analyze(
        parsedArtifact(new GcLogData(events, GcLogFormat.UNIFIED, Map.of())),
        AnalysisContext.defaults());

    assertThat(findings)
        .filteredOn(finding -> finding.title().equals("Repeated Full GCs failed to reclaim heap"))
        .singleElement()
        .satisfies(finding -> {
          assertThat(finding.reliability()).isEqualTo(FindingReliability.DERIVED);
          assertThat(finding.explanation()).contains("reclaimed 0.00 MB in total");
        });
  }

  private static List<GcEvent> phaseFourEvents() {
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    return List.of(
        event("gc-0", base.plusSeconds(1), 1, 120, "G1 Evacuation Pause", false, false, 512, 384, 256, 320, 256),
        event("gc-1", base.plusSeconds(5), 5, 650, "G1 Humongous Allocation", false, true, 768, 704, 704, 768, 512),
        event("gc-2", base.plusSeconds(10), 10, 2200, "System.gc()", true, false, 1024, 832, 832, 896, 256),
        event("gc-3", base.plusSeconds(16), 16, 900, "Metadata GC Threshold", false, false, 1100, 980, 980, 1044, 640),
        event("gc-4", base.plusSeconds(22), 22, 750, "Metadata GC Threshold", false, false, 1200, 1080, 1080, 1144, 640));
  }

  private static GcEvent event(
      String id,
      Instant timestamp,
      long uptimeSeconds,
      long durationMillis,
      String cause,
      boolean fullGc,
      boolean humongous,
      double heapBeforeMb,
      double heapAfterMb,
      double oldBeforeMb,
      double oldAfterMb,
      double edenBeforeMb) {
    return new GcEvent(
        id,
        timestamp,
        Duration.ofSeconds(uptimeSeconds),
        Duration.ofMillis(durationMillis),
        "Pause " + cause,
        GcCollector.G1,
        cause,
        fullGc,
        false,
        cause.equals("System.gc()"),
        humongous,
        heapBeforeMb,
        heapAfterMb,
        2048.0,
        edenBeforeMb,
        0.0,
        oldBeforeMb,
        oldAfterMb,
        null,
        null,
        null,
        null,
        Map.of("format", GcLogFormat.UNIFIED));
  }

  private static ParsedArtifact parsedArtifact(GcLogData data) {
    Artifact artifact = new Artifact(
        "gc-sample",
        ArtifactType.GC_LOG,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of("filename", "gc.log"),
        JvmFlavour.HOTSPOT,
        Path.of("/tmp/gc.log"));
    return new ParsedArtifact(
        artifact,
        List.of(),
        Map.of(GcAnalyzer.GC_DATA_KEY, data),
        List.of());
  }
}
