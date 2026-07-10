package io.helix.analyzer.gc;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.Finding;
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
            "GC throughput below target",
            "GC cause breakdown",
            "Explicit System.gc() detected",
            "Heap-after-GC leak gradient",
            "Humongous allocation GC pattern",
            "Frequent metadata-triggered GC",
            "Allocation rate summary",
            "High promotion rate");
    assertThat(findings)
        .filteredOn(finding -> finding.title().equals("Explicit System.gc() detected"))
        .singleElement()
        .satisfies(finding -> assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH));
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
