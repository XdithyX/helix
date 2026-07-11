package io.helix.reporter.text;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.JvmProfile;
import io.helix.core.spi.ReportOptions;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextReporterTest {
  @Test
  void rendersHumanReadableSummary() throws Exception {
    DiagnosticReport report = reportFixture();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    new TextReporter().render(report, out, ReportOptions.forFormat("text"));

    String text = out.toString(StandardCharsets.UTF_8);
    assertThat(text).contains("Helix Diagnostic Report");
    assertThat(text).contains("Artifacts: 1");
    assertThat(text).contains("No findings were produced");
  }

  private static DiagnosticReport reportFixture() {
    Artifact artifact = new Artifact(
        "artifact-1",
        ArtifactType.THREAD_DUMP,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of("filename", "threads.txt"),
        JvmFlavour.UNKNOWN,
        Path.of("/tmp/threads.txt"));
    return new DiagnosticReport(
        "report-1",
        Instant.parse("2026-01-01T00:00:01Z"),
        List.of(artifact),
        List.of(),
        List.of(),
        new CorrelationSummary("Phase 1 only.", List.of(), Map.of()),
        JvmProfile.unknown(),
        null);
  }
}
