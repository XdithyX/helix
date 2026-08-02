package io.helix.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class HelixMainTest {
  @TempDir
  Path tempDir;

  @Test
  void versionPrintsComponentInformation() {
    StringWriter out = new StringWriter();
    CommandLine commandLine = new CommandLine(new HelixMain());
    commandLine.setOut(new PrintWriter(out));

    int exitCode = commandLine.execute("version");

    assertThat(exitCode).isZero();
    assertThat(out.toString()).contains("Helix").contains("helix-core");
  }

  @Test
  void analyzeWritesJsonReportEnvelopeForUnsupportedCurrentPhaseArtifact() throws Exception {
    Path artifact = tempDir.resolve("threads.txt");
    Path reports = tempDir.resolve("reports");
    Files.writeString(artifact, "phase-1-placeholder");

    StringWriter out = new StringWriter();
    CommandLine commandLine = new CommandLine(new HelixMain());
    commandLine.setOut(new PrintWriter(out));

    int exitCode = commandLine.execute(
        "--output", "json",
        "--output-dir", reports.toString(),
        "analyze",
        artifact.toString());

    assertThat(exitCode).isZero();
    assertThat(out.toString()).contains("Wrote json report");
    try (var stream = Files.list(reports)) {
      List<Path> generated = stream.toList();
      assertThat(generated).hasSize(1);
      assertThat(Files.readString(generated.getFirst()))
          .contains("\"type\" : \"THREAD_DUMP\"")
          .contains("\"findings\" : [ ]");
    }
  }

  @Test
  void questionModeRejectsExplicitNoLlm() throws Exception {
    Path artifact = tempDir.resolve("threads.txt");
    Files.writeString(artifact, "thread dump");
    StringWriter err = new StringWriter();
    CommandLine commandLine = new CommandLine(new HelixMain());
    commandLine.setErr(new PrintWriter(err));

    int exitCode = commandLine.execute(
        "--no-llm",
        "analyze",
        "--question", "Why is the JVM unresponsive?",
        artifact.toString());

    assertThat(exitCode).isEqualTo(2);
    assertThat(err.toString()).contains("--question requires an available LLM");
  }
}
