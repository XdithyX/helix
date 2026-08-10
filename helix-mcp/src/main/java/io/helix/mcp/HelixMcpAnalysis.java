package io.helix.mcp;

import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.core.model.Artifact;
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
import io.helix.core.spi.Correlator;
import io.helix.core.spi.ParseContext;
import io.helix.core.spi.ParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.stream.Stream;

final class HelixMcpAnalysis {
  private static final Map<String, Object> ANALYSIS_OPTIONS = Map.of(
      "heap.leakThresholdPercent", 10.0,
      "heap.maxDominators", 20,
      "gc.pauseWarningMs", 500.0,
      "gc.throughputWarningPercent", 95.0,
      "jfr.slowIoThresholdMs", 100.0);

  InvestigationContext analyze(List<Path> artifactPaths) {
    return analyze(artifactPaths, null, false);
  }

  InvestigationContext analyze(
      List<Path> artifactPaths,
      ArtifactType artifactTypeOverride,
      boolean correlate) {
    if (artifactPaths == null || artifactPaths.isEmpty()) {
      throw new IllegalArgumentException("At least one JVM artifact path is required.");
    }

    List<ArtifactParser> parsers = loadParsers();
    List<Analyzer> analyzers = loadAnalyzers();
    List<ParsedArtifact> parsedArtifacts = new ArrayList<>();
    List<Finding> findings = new ArrayList<>();
    List<TimelineEvent> timeline = new ArrayList<>();

    for (Path suppliedPath : artifactPaths) {
      Path path = suppliedPath.toAbsolutePath().normalize();
      if (!Files.isRegularFile(path)) {
        throw new IllegalArgumentException("Artifact file does not exist or is not a regular file: " + path);
      }
      ArtifactParser parser = parsers.stream()
          .filter(candidate -> candidate.canParse(path, artifactTypeOverride))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException(noParserMessage(path, artifactTypeOverride)));
      ParsedArtifact parsed = parse(parser, path);
      parsedArtifacts.add(parsed);
      timeline.addAll(parsed.timelineEvents());
      analyzers.stream()
          .filter(analyzer -> analyzer.supportedTypes().contains(parsed.artifact().type()))
          .forEach(analyzer -> findings.addAll(
              analyzer.analyze(parsed, new AnalysisContext(null, ANALYSIS_OPTIONS))));
    }

    CorrelationSummary correlationSummary = CorrelationSummary.empty();
    if (correlate) {
      List<Correlator> correlators = ServiceLoader.load(Correlator.class).stream()
          .map(ServiceLoader.Provider::get)
          .sorted(Comparator.comparing(correlator -> correlator.getClass().getName()))
          .toList();
      if (!correlators.isEmpty()) {
        CorrelationResult result = correlators.getFirst().correlate(parsedArtifacts, timeline);
        findings.addAll(result.findings());
        timeline = new ArrayList<>(result.timeline());
        correlationSummary = result.summary();
      }
    }

    DiagnosticReport report = new DiagnosticReport(
        "helix-mcp-" + UUID.randomUUID(),
        Instant.now(),
        parsedArtifacts.stream().map(ParsedArtifact::artifact).toList(),
        findings,
        timeline,
        correlationSummary,
        JvmProfile.unknown(),
        null);
    return new InvestigationContext("", report, parsedArtifacts);
  }

  static ArtifactType resolveArtifactType(String hint) {
    if (hint == null || hint.isBlank()) {
      throw new IllegalArgumentException(
          "Artifact type override must be one of heap|thread|gclog|jfr|crash.");
    }
    return switch (hint.toLowerCase(Locale.ROOT)) {
      case "heap", "hprof", "heap_dump" -> ArtifactType.HEAP_DUMP;
      case "thread", "threads", "threaddump", "thread_dump" -> ArtifactType.THREAD_DUMP;
      case "gc", "gclog", "gc_log" -> ArtifactType.GC_LOG;
      case "jfr" -> ArtifactType.JFR;
      case "crash", "crashlog", "crash_log", "hs_err" -> ArtifactType.CRASH_LOG;
      default -> throw new IllegalArgumentException(
          "Unsupported artifact type override: " + hint
              + " (expected heap|thread|gclog|jfr|crash)");
    };
  }

  private static List<ArtifactParser> loadParsers() {
    return ServiceLoader.load(ArtifactParser.class).stream()
        .map(ServiceLoader.Provider::get)
        .sorted(Comparator.comparing(parser -> parser.artifactType().name()))
        .toList();
  }

  private static List<Analyzer> loadAnalyzers() {
    return ServiceLoader.load(Analyzer.class).stream()
        .map(ServiceLoader.Provider::get)
        .sorted(Comparator.comparing(Analyzer::analyzerId))
        .toList();
  }

  static ParsedArtifact parse(ArtifactParser parser, Path path) {
    if (parser.artifactType() == ArtifactType.HEAP_DUMP) {
      return parseIsolatedHeapDump(parser, path);
    }
    return parseDirect(parser, path);
  }

  private static ParsedArtifact parseIsolatedHeapDump(ArtifactParser parser, Path originalPath) {
    Path ownedDirectory;
    try {
      ownedDirectory = Files.createTempDirectory("helix-mcp-hprof-");
    } catch (IOException e) {
      throw new IllegalArgumentException("Unable to create an isolated HPROF workspace.", e);
    }

    Throwable primaryFailure = null;
    try {
      Path stagedPath = ownedDirectory.resolve(originalPath.getFileName());
      stageHeapDump(originalPath, stagedPath);
      return withOriginalSourcePath(parseDirect(parser, stagedPath), originalPath);
    } catch (RuntimeException | Error e) {
      primaryFailure = e;
      throw e;
    } finally {
      try {
        deleteOwnedDirectory(ownedDirectory);
      } catch (IOException cleanupFailure) {
        if (primaryFailure != null) {
          primaryFailure.addSuppressed(cleanupFailure);
        } else {
          throw new IllegalStateException(
              "Failed to remove isolated HPROF workspace: " + ownedDirectory,
              cleanupFailure);
        }
      }
    }
  }

  private static void stageHeapDump(Path originalPath, Path stagedPath) {
    try {
      Files.createLink(stagedPath, originalPath);
      return;
    } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
      // A copy works across filesystems and where hard links are not permitted.
    }

    try {
      Files.deleteIfExists(stagedPath);
      Files.copy(originalPath, stagedPath, StandardCopyOption.COPY_ATTRIBUTES);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Unable to stage HPROF artifact in an isolated workspace: " + originalPath,
          e);
    }
  }

  private static ParsedArtifact withOriginalSourcePath(
      ParsedArtifact parsed,
      Path originalPath) {
    Artifact staged = parsed.artifact();
    Artifact remapped = new Artifact(
        staged.id(),
        staged.type(),
        staged.capturedAt(),
        staged.metadata(),
        staged.jvmFlavour(),
        originalPath.toAbsolutePath().normalize());
    return new ParsedArtifact(
        remapped,
        parsed.timelineEvents(),
        parsed.data(),
        parsed.warnings());
  }

  private static void deleteOwnedDirectory(Path ownedDirectory) throws IOException {
    try (Stream<Path> paths = Files.walk(ownedDirectory)) {
      List<Path> deletionOrder = paths.sorted(Comparator.reverseOrder()).toList();
      for (Path path : deletionOrder) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static ParsedArtifact parseDirect(ArtifactParser parser, Path path) {
    try {
      return parser.parse(path, ParseContext.defaults());
    } catch (ParseException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  private static String noParserMessage(Path path, ArtifactType artifactTypeOverride) {
    if (artifactTypeOverride == null) {
      return "No Helix parser accepts artifact: " + path
          + "; use --type heap|thread|gclog|jfr|crash to override detection.";
    }
    return "No Helix parser accepts artifact " + path
        + " as " + artifactTypeOverride.name() + ".";
  }
}
