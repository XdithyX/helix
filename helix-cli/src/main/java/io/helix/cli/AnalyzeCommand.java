package io.helix.cli;

import io.helix.agent.core.AgentContext;
import io.helix.agent.core.AgentOrchestrator;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.TimelineEvent;
import io.helix.core.spi.AnalysisContext;
import io.helix.core.spi.ArtifactParser;
import io.helix.core.spi.Analyzer;
import io.helix.core.spi.CorrelationResult;
import io.helix.core.spi.Correlator;
import io.helix.core.spi.ParseContext;
import io.helix.core.spi.ParseException;
import io.helix.core.spi.ReportOptions;
import io.helix.core.spi.Reporter;
import io.helix.core.spi.ReasoningProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "analyze",
    description = "Analyze one or more artifact files.")
final class AnalyzeCommand implements Callable<Integer> {
  @ParentCommand
  HelixMain parent;

  @Spec
  CommandSpec spec;

  @Parameters(arity = "1..*", paramLabel = "<file>", description = "Artifact file(s) to analyze.")
  List<Path> files;

  @Option(
      names = "--type",
      paramLabel = "<type>",
      description = "Override artifact type: heap|thread|gclog|jfr|crash.")
  String typeHint;

  @Option(names = "--since", paramLabel = "<timestamp>", description = "Only analyze events after this ISO-8601 timestamp.")
  String since;

  @Option(names = "--until", paramLabel = "<timestamp>", description = "Only analyze events before this ISO-8601 timestamp.")
  String until;

  @Option(names = "--correlate", description = "Enable cross-artifact correlation.")
  boolean correlate;

  @Option(names = "--framework", paramLabel = "<name>", description = "Framework hint: spark|spring|netty.")
  String framework;

  @Option(names = "--threshold", paramLabel = "<severity>", description = "Minimum severity: CRITICAL|HIGH|MEDIUM|LOW.")
  FindingSeverity threshold;

  @Override
  public Integer call() throws IOException {
    validateTimeWindow();

    List<Path> normalizedFiles = files.stream()
        .map(path -> path.toAbsolutePath().normalize())
        .toList();
    for (Path file : normalizedFiles) {
      if (!Files.isRegularFile(file)) {
        throw new CommandLine.ParameterException(
            spec.commandLine(), "Artifact file does not exist or is not a regular file: " + file);
      }
    }

    DiagnosticReport report = analyze(normalizedFiles);
    Reporter reporter = reporterFor(parent.outputFormat);
    Path outputFile = writeReport(report, reporter);

    spec.commandLine().getOut().printf(
        "Wrote %s report to %s%n",
        reporter.format(),
        outputFile);

    if (parent.verbose) {
      spec.commandLine().getErr().printf(
          "Analyzed %d artifact(s); produced %d finding(s).%n",
          report.artifacts().size(),
          report.findings().size());
    }
    return 0;
  }

  private DiagnosticReport analyze(List<Path> normalizedFiles) throws IOException {
    ParserRegistry parserRegistry = ParserRegistry.load();
    AnalyzerRegistry analyzerRegistry = AnalyzerRegistry.load();
    List<ParsedArtifact> parsedArtifacts = new ArrayList<>();
    List<Finding> findings = new ArrayList<>();
    List<TimelineEvent> timeline = new ArrayList<>();

    for (Path file : normalizedFiles) {
      ArtifactType hint = typeHint == null ? null : ArtifactTypeResolver.resolve(file, typeHint);
      Optional<ArtifactParser> parser = parserRegistry.find(file, hint);
      if (parser.isEmpty()) {
        parsedArtifacts.add(DiagnosticReportFactory.phaseOneParsedArtifact(file, typeHint));
        continue;
      }

      ParsedArtifact parsed = parse(parser.get(), file);
      parsedArtifacts.add(parsed);
      timeline.addAll(parsed.timelineEvents());
      for (Analyzer analyzer : analyzerRegistry.supporting(parsed.artifact().type())) {
        findings.addAll(analyzer.analyze(parsed, analysisContext()));
      }
    }

    CorrelationResult correlationResult = null;
    if (correlate) {
      Optional<Correlator> correlator = CorrelatorRegistry.load().first();
      if (correlator.isPresent()) {
        correlationResult = correlator.get().correlate(parsedArtifacts, timeline);
        findings.addAll(correlationResult.findings());
        timeline = new ArrayList<>(correlationResult.timeline());
      }
    }

    List<Finding> filteredFindings = findings.stream()
        .filter(this::passesThreshold)
        .toList();
    DiagnosticReport baseReport =
        DiagnosticReportFactory.fromParsedArtifacts(parsedArtifacts, filteredFindings, timeline, correlationResult);
    return new AgentOrchestrator().analyze(baseReport, agentContext(baseReport));
  }

  private ParsedArtifact parse(ArtifactParser parser, Path file) {
    try {
      return parser.parse(file, ParseContext.defaults());
    } catch (ParseException e) {
      throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage(), e);
    }
  }

  private AnalysisContext analysisContext() {
    return new AnalysisContext(
        null,
        Map.of(
            "heap.leakThresholdPercent", 10.0,
            "heap.maxDominators", 20,
            "gc.pauseWarningMs", 500.0,
            "gc.throughputWarningPercent", 95.0,
            "jfr.slowIoThresholdMs", 100.0));
  }

  private AgentContext agentContext(DiagnosticReport report) {
    ReasoningProvider provider = parent.noLlm ? null : ServiceLoader.load(ReasoningProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .filter(ReasoningProvider::isAvailable)
        .findFirst()
        .orElse(null);
    return new AgentContext(
        report.jvmProfile(),
        null,
        provider,
        List.of(),
        null,
        Map.of("framework", framework == null ? "" : framework));
  }

  private boolean passesThreshold(Finding finding) {
    if (threshold == null) {
      return true;
    }
    return finding.severity().ordinal() <= threshold.ordinal();
  }

  private Reporter reporterFor(String format) {
    Optional<Reporter> reporter = ReporterRegistry.load().find(format);
    if (reporter.isEmpty()) {
      throw new CommandLine.ParameterException(
          spec.commandLine(),
          "Unsupported output format: " + format + " (available: "
              + String.join(", ", ReporterRegistry.load().formats()) + ")");
    }
    return reporter.get();
  }

  private Path writeReport(DiagnosticReport report, Reporter reporter) throws IOException {
    Path outputDir = parent.outputDir.toAbsolutePath().normalize();
    Files.createDirectories(outputDir);
    Path outputFile = outputDir.resolve(report.reportId() + "." + extensionFor(reporter));
    try (OutputStream out = Files.newOutputStream(outputFile)) {
      reporter.render(report, out, ReportOptions.forFormat(reporter.format()));
    }
    return outputFile;
  }

  private static String extensionFor(Reporter reporter) {
    return switch (reporter.format()) {
      case "json" -> "json";
      case "text" -> "txt";
      default -> reporter.format();
    };
  }

  private void validateTimeWindow() {
    Instant sinceInstant = parseOptionalInstant(since, "--since");
    Instant untilInstant = parseOptionalInstant(until, "--until");
    if (sinceInstant != null && untilInstant != null && untilInstant.isBefore(sinceInstant)) {
      throw new CommandLine.ParameterException(
          spec.commandLine(), "--until must be the same as or later than --since");
    }
  }

  private Instant parseOptionalInstant(String value, String optionName) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new CommandLine.ParameterException(
          spec.commandLine(), optionName + " must be an ISO-8601 instant: " + value, e);
    }
  }
}
