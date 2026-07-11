package io.helix.reporter.json;

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

class JsonReporterTest {
  @Test
  void rendersReportAsJson() throws Exception {
    DiagnosticReport report = reportFixture();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    new JsonReporter().render(report, out, ReportOptions.forFormat("json"));

    String json = out.toString(StandardCharsets.UTF_8);
    assertThat(json).contains("\"reportId\" : \"report-1\"");
    assertThat(json).contains("\"type\" : \"HEAP_DUMP\"");
    assertThat(json).contains("\"sourcePath\" : \"/tmp/heap.hprof\"");
  }

  private static DiagnosticReport reportFixture() {
    Artifact artifact = new Artifact(
        "artifact-1",
        ArtifactType.HEAP_DUMP,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of("filename", "heap.hprof"),
        JvmFlavour.UNKNOWN,
        Path.of("/tmp/heap.hprof"));
    return new DiagnosticReport(
        "report-1",
        Instant.parse("2026-01-01T00:00:01Z"),
        List.of(artifact),
        List.of(),
        List.of(),
        CorrelationSummary.empty(),
        JvmProfile.unknown(),
        null);
  }
}
