package io.helix.reporter.text;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingReliability;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.JvmProfile;
import io.helix.core.model.InvestigationResult;
import io.helix.core.model.InvestigationStatus;
import io.helix.core.model.ToolTrace;
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

  @Test
  void rendersFindingReliability() throws Exception {
    DiagnosticReport report = reportFixture(finding());
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    new TextReporter().render(report, out, ReportOptions.forFormat("text"));

    assertThat(out.toString(StandardCharsets.UTF_8)).contains("Reliability: HEURISTIC");
  }

  @Test
  void rendersInvestigationAnswerAndToolTrace() throws Exception {
    DiagnosticReport report = reportFixture().withInvestigation(new InvestigationResult(
        "Why is the JVM unresponsive?",
        InvestigationStatus.CONCLUDED,
        "Workers are blocked on one monitor.",
        List.of(new ToolTrace(
            "query_threads",
            Map.of("operation", "LOCK_GRAPH"),
            Map.of("status", "SUCCESS", "summary", "Found 4 lock-wait edges.")))));
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    new TextReporter().render(report, out, ReportOptions.forFormat("text"));

    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("Investigation")
        .contains("Status: CONCLUDED")
        .contains("Workers are blocked on one monitor.")
        .contains("query_threads [SUCCESS]: Found 4 lock-wait edges.");
  }

  private static DiagnosticReport reportFixture() {
    return reportFixture(null);
  }

  private static DiagnosticReport reportFixture(Finding finding) {
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
        finding == null ? List.of() : List.of(finding),
        List.of(),
        new CorrelationSummary("Phase 1 only.", List.of(), Map.of()),
        JvmProfile.unknown(),
        null);
  }

  private static Finding finding() {
    return new Finding(
        "finding-1",
        FindingSeverity.MEDIUM,
        FindingCategory.THREADING,
        "Pool exhaustion suspect",
        "All observed workers are active.",
        0.75,
        List.of("artifact-1"),
        List.of(),
        List.of(),
        Map.of(),
        FindingReliability.HEURISTIC);
  }
}
