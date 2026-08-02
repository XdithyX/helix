package io.helix.reporter.text;

import io.helix.core.model.Artifact;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.InvestigationResult;
import io.helix.core.model.Remediation;
import io.helix.core.model.ToolTrace;
import io.helix.core.spi.ReportOptions;
import io.helix.core.spi.Reporter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;

public final class TextReporter implements Reporter {
  @Override
  public void render(DiagnosticReport report, OutputStream out, ReportOptions options)
      throws IOException {
    PrintWriter writer =
        new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), false);

    writer.println("Helix Diagnostic Report");
    writer.println("=======================");
    writer.println("Report ID: " + report.reportId());
    writer.println("Generated: " + report.generatedAt());
    writer.println();

    writer.println("Artifacts: " + report.artifacts().size());
    for (Artifact artifact : report.artifacts()) {
      writer.printf(
          "- %s [%s] %s%n",
          artifact.id(),
          artifact.type(),
          artifact.sourcePath());
    }
    writer.println();

    Map<FindingSeverity, Integer> severityCounts = severityCounts(report);
    writer.println("Findings: " + report.findings().size());
    for (FindingSeverity severity : FindingSeverity.values()) {
      writer.printf("- %s: %d%n", severity, severityCounts.get(severity));
    }
    writer.println();

    if (report.findings().isEmpty()) {
      writer.println("No findings were produced. Phase 1 does not parse or analyze artifacts yet.");
    } else {
      report.findings().stream()
          .sorted(Comparator.comparing(Finding::severity).thenComparing(Finding::title))
          .forEach(finding -> printFinding(writer, finding, options));
    }

    String narrative = report.correlationSummary().narrative();
    if (!narrative.isBlank()) {
      writer.println();
      writer.println("Correlation Summary");
      writer.println("-------------------");
      writer.println(narrative);
    }

    if (report.llmNarrative() != null && !report.llmNarrative().isBlank()) {
      writer.println();
      writer.println("Reasoning Summary");
      writer.println("-----------------");
      writer.println(report.llmNarrative());
    }

    if (report.investigation() != null) {
      printInvestigation(writer, report.investigation(), options);
    }

    writer.flush();
  }

  @Override
  public String format() {
    return "text";
  }

  private static Map<FindingSeverity, Integer> severityCounts(DiagnosticReport report) {
    Map<FindingSeverity, Integer> counts = new EnumMap<>(FindingSeverity.class);
    for (FindingSeverity severity : FindingSeverity.values()) {
      counts.put(severity, 0);
    }
    for (Finding finding : report.findings()) {
      counts.computeIfPresent(finding.severity(), (ignored, count) -> count + 1);
    }
    return counts;
  }

  private static void printFinding(PrintWriter writer, Finding finding, ReportOptions options) {
    writer.printf("[%s] %s%n", finding.severity(), finding.title());
    writer.println(finding.explanation());
    writer.println("Reliability: " + finding.reliability());
    writer.printf("Confidence: %.2f%n", finding.confidence());
    for (Remediation remediation : finding.remediations()) {
      writer.printf("- Remediation (%s): %s%n", remediation.type(), remediation.description());
      if (!remediation.suggestion().isBlank()) {
        writer.println("  Suggestion: " + remediation.suggestion());
      }
    }
    if (options.includeRawData() && !finding.rawData().isEmpty()) {
      writer.println("Raw data: " + finding.rawData());
    }
    writer.println();
  }

  private static void printInvestigation(
      PrintWriter writer,
      InvestigationResult investigation,
      ReportOptions options) {
    writer.println();
    writer.println("Investigation");
    writer.println("-------------");
    writer.println("Question: " + investigation.question());
    writer.println("Status: " + investigation.status());
    writer.println("Answer: " + investigation.answer());
    writer.println("Tool calls: " + investigation.toolTraces().size());
    for (ToolTrace trace : investigation.toolTraces()) {
      Object status = trace.result().getOrDefault("status", "");
      Object summary = trace.result().getOrDefault("summary", "");
      writer.printf("- %s [%s]: %s%n", trace.toolName(), status, summary);
      if (options.includeRawData()) {
        writer.println("  Arguments: " + trace.arguments());
        writer.println("  Result: " + trace.result());
      }
    }
  }
}
