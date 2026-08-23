package io.helix.cli;

import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.JvmProfile;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.TimelineEvent;
import io.helix.core.spi.AnalysisContext;
import io.helix.core.spi.ArtifactParser;
import io.helix.core.spi.Analyzer;
import io.helix.core.spi.CorrelationResult;
import io.helix.core.spi.ParseContext;
import io.helix.core.spi.ParseException;
import io.helix.investigations.InvestigationId;
import io.helix.investigations.InvestigationSummary;
import io.helix.investigations.LocalWorkspaceInvestigationStore;
import io.helix.investigations.StoredInvestigation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "investigation",
    description = "Manage persistent investigations in a local workspace.",
    mixinStandardHelpOptions = true,
    subcommands = {
        InvestigationCommand.CreateCommand.class,
        InvestigationCommand.ListCommand.class,
        InvestigationCommand.ShowCommand.class
    })
public final class InvestigationCommand implements Callable<Integer> {

  @Spec
  CommandSpec spec;

  @Override
  public Integer call() {
    spec.commandLine().usage(spec.commandLine().getOut());
    return 0;
  }

  // ---------------------------------------------------------------------------
  // create
  // ---------------------------------------------------------------------------

  @Command(name = "create", description = "Import artifact(s) and create a new investigation.")
  static final class CreateCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(
        names = "--workspace",
        paramLabel = "<path>",
        required = true,
        description = "Path to the Helix workspace directory.")
    Path workspace;

    @Option(
        names = "--name",
        paramLabel = "<name>",
        required = true,
        description = "Human-readable investigation name.")
    String name;

    @Option(names = "--correlate", description = "Enable cross-artifact correlation.")
    boolean correlate;

    @Parameters(arity = "1..*", paramLabel = "<file>", description = "Artifact file(s) to import.")
    List<Path> files;

    @Override
    public Integer call() {
      List<Path> normalized = validateFiles();

      InvestigationId id = InvestigationId.generate();
      Instant createdAt = Instant.now();

      spec.commandLine().getOut().printf("Importing investigation %s (\"%s\")...%n", id, name);

      LocalWorkspaceInvestigationStore store = new LocalWorkspaceInvestigationStore(workspace);

      InvestigationContext context;
      try {
        context = runPipeline(normalized, correlate);
      } catch (Exception e) {
        // Save a FAILED record so the user can inspect it via `show`.
        List<ArtifactType> types = normalized.stream()
            .map(p -> {
              try {
                return ArtifactTypeResolver.resolve(p, null);
              } catch (Exception ignored) {
                return ArtifactType.THREAD_DUMP; // unknown fallback
              }
            })
            .distinct()
            .toList();
        InvestigationSummary failed = InvestigationSummary.failed(
            id, name, createdAt, types, correlate, safeMessage(e));
        store.saveFailed(failed);
        spec.commandLine().getErr().printf(
            "Import failed: %s%nA FAILED record has been saved as %s.%n", safeMessage(e), id);
        return 1;
      }

      store.saveReady(id, name, createdAt, correlate, context);
      spec.commandLine().getOut().printf(
          "Investigation %s is READY (%d artifact(s), %d finding(s)).%n",
          id,
          context.report().artifacts().size(),
          context.report().findings().size());
      return 0;
    }

    private List<Path> validateFiles() {
      List<Path> normalized = new ArrayList<>();
      for (Path file : files) {
        Path abs = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) {
          throw new CommandLine.ParameterException(
              spec.commandLine(),
              "Artifact file does not exist or is not a regular file: " + abs);
        }
        normalized.add(abs);
      }
      return normalized;
    }
  }

  // ---------------------------------------------------------------------------
  // list
  // ---------------------------------------------------------------------------

  @Command(name = "list", description = "List investigations in a workspace.")
  static final class ListCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(
        names = "--workspace",
        paramLabel = "<path>",
        required = true,
        description = "Path to the Helix workspace directory.")
    Path workspace;

    @Override
    public Integer call() {
      LocalWorkspaceInvestigationStore store = new LocalWorkspaceInvestigationStore(workspace);
      List<InvestigationSummary> summaries = store.list();
      if (summaries.isEmpty()) {
        spec.commandLine().getOut().println("No investigations found in workspace: " + workspace);
        return 0;
      }
      spec.commandLine().getOut().printf(
          "%-30s %-8s %-30s %s%n", "ID", "STATUS", "CREATED", "NAME");
      for (InvestigationSummary s : summaries) {
        spec.commandLine().getOut().printf(
            "%-30s %-8s %-30s %s%n",
            s.id(),
            s.status().name(),
            s.createdAt(),
            s.name());
      }
      return 0;
    }
  }

  // ---------------------------------------------------------------------------
  // show
  // ---------------------------------------------------------------------------

  @Command(name = "show", description = "Show details of a single investigation.")
  static final class ShowCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(
        names = "--workspace",
        paramLabel = "<path>",
        required = true,
        description = "Path to the Helix workspace directory.")
    Path workspace;

    @Parameters(arity = "1", paramLabel = "<id>", description = "Investigation ID.")
    String investigationId;

    @Override
    public Integer call() {
      InvestigationId id;
      try {
        id = new InvestigationId(investigationId);
      } catch (IllegalArgumentException e) {
        throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
      }

      LocalWorkspaceInvestigationStore store = new LocalWorkspaceInvestigationStore(workspace);
      // Try to find a ready investigation for full details.
      Optional<StoredInvestigation> stored = store.find(id);
      if (stored.isPresent()) {
        InvestigationSummary s = stored.get().summary();
        InvestigationContext ctx = stored.get().context();
        spec.commandLine().getOut().printf("ID:          %s%n", s.id());
        spec.commandLine().getOut().printf("Name:        %s%n", s.name());
        spec.commandLine().getOut().printf("Status:      %s%n", s.status());
        spec.commandLine().getOut().printf("Created:     %s%n", s.createdAt());
        spec.commandLine().getOut().printf("Correlate:   %s%n", s.correlate());
        spec.commandLine().getOut().printf("Artifacts:   %s%n", s.artifactTypes());
        spec.commandLine().getOut().printf("Findings:    %d%n", ctx.report().findings().size());
        spec.commandLine().getOut().printf("Timeline:    %d events%n", ctx.report().timeline().size());
        return 0;
      }

      // Not READY — check if a FAILED record exists via list.
      List<InvestigationSummary> all = store.list();
      Optional<InvestigationSummary> failed = all.stream()
          .filter(s -> s.id().equals(id))
          .findFirst();
      if (failed.isPresent()) {
        InvestigationSummary s = failed.get();
        spec.commandLine().getOut().printf("ID:          %s%n", s.id());
        spec.commandLine().getOut().printf("Name:        %s%n", s.name());
        spec.commandLine().getOut().printf("Status:      %s%n", s.status());
        spec.commandLine().getOut().printf("Created:     %s%n", s.createdAt());
        if (!s.failureReason().isBlank()) {
          spec.commandLine().getOut().printf("Failure:     %s%n", s.failureReason());
        }
        return 0;
      }

      spec.commandLine().getErr().println("Investigation not found: " + investigationId);
      return 1;
    }
  }

  // ---------------------------------------------------------------------------
  // pipeline (shared with create)
  // ---------------------------------------------------------------------------

  private static final Map<String, Object> ANALYSIS_OPTIONS = Map.of(
      "heap.leakThresholdPercent", 10.0,
      "heap.maxDominators", 20,
      "gc.pauseWarningMs", 500.0,
      "gc.throughputWarningPercent", 95.0,
      "jfr.slowIoThresholdMs", 100.0);

  static InvestigationContext runPipeline(List<Path> files, boolean correlate) {
    ParserRegistry parserRegistry = ParserRegistry.load();
    AnalyzerRegistry analyzerRegistry = AnalyzerRegistry.load();

    List<ParsedArtifact> parsedArtifacts = new ArrayList<>();
    List<Finding> findings = new ArrayList<>();
    List<TimelineEvent> timeline = new ArrayList<>();

    for (Path file : files) {
      ArtifactParser parser = parserRegistry.find(file, null)
          .orElseThrow(() -> new IllegalArgumentException(
              "No Helix parser accepts: " + file
                  + "; use --type heap|thread|gclog|jfr|crash to override."));
      ParsedArtifact parsed;
      try {
        parsed = parser.parse(file, ParseContext.defaults());
      } catch (ParseException e) {
        throw new IllegalArgumentException(e.getMessage(), e);
      }
      parsedArtifacts.add(parsed);
      timeline.addAll(parsed.timelineEvents());
      for (Analyzer analyzer : analyzerRegistry.supporting(parsed.artifact().type())) {
        findings.addAll(analyzer.analyze(parsed, new AnalysisContext(null, ANALYSIS_OPTIONS)));
      }
    }

    CorrelationSummary correlationSummary = CorrelationSummary.empty();
    if (correlate) {
      Optional<io.helix.core.spi.Correlator> correlator = CorrelatorRegistry.load().first();
      if (correlator.isPresent()) {
        CorrelationResult result = correlator.get().correlate(parsedArtifacts, timeline);
        findings.addAll(result.findings());
        timeline = new ArrayList<>(result.timeline());
        correlationSummary = result.summary();
      }
    }

    DiagnosticReport report = new DiagnosticReport(
        "inv-" + UUID.randomUUID(),
        Instant.now(),
        parsedArtifacts.stream().map(ParsedArtifact::artifact).toList(),
        findings,
        timeline,
        correlationSummary,
        JvmProfile.unknown(),
        null);
    return new InvestigationContext("", report, parsedArtifacts);
  }

  private static String safeMessage(Throwable t) {
    String msg = t.getMessage();
    return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
  }
}
