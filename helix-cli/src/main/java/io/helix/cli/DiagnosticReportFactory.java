package io.helix.cli;

import io.helix.core.model.Artifact;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.JvmProfile;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.TimelineEvent;
import io.helix.core.spi.CorrelationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class DiagnosticReportFactory {
  private DiagnosticReportFactory() {}

  static DiagnosticReport phaseOneReport(List<Path> files, String typeHint) throws IOException {
    List<ParsedArtifact> parsedArtifacts = new ArrayList<>();
    for (Path file : files) {
      parsedArtifacts.add(phaseOneParsedArtifact(file, typeHint));
    }
    return fromParsedArtifacts(parsedArtifacts, List.of());
  }

  static ParsedArtifact phaseOneParsedArtifact(Path file, String typeHint) throws IOException {
    Artifact artifact = new Artifact(
        fallbackArtifactId(file),
        ArtifactTypeResolver.resolve(file, typeHint),
        Files.getLastModifiedTime(file).toInstant(),
        Map.of(
            "filename", file.getFileName().toString(),
            "size.bytes", Long.toString(Files.size(file)),
            "analysis.status", "unsupported-in-current-phase"),
        JvmFlavour.UNKNOWN,
        file);
    return new ParsedArtifact(
        artifact,
        List.of(),
        Map.of(),
        List.of("No parser is implemented for this artifact type in the current phase."));
  }

  static DiagnosticReport fromParsedArtifacts(
      List<ParsedArtifact> parsedArtifacts,
      List<Finding> findings) {
    return fromParsedArtifacts(
        parsedArtifacts,
        findings,
        parsedArtifacts.stream()
            .flatMap(parsed -> parsed.timelineEvents().stream())
            .toList(),
        null);
  }

  static DiagnosticReport fromParsedArtifacts(
      List<ParsedArtifact> parsedArtifacts,
      List<Finding> findings,
      List<TimelineEvent> timeline,
      CorrelationResult correlationResult) {
    Instant generatedAt = Instant.now();
    List<Artifact> artifacts = parsedArtifacts.stream()
        .map(ParsedArtifact::artifact)
        .toList();
    return new DiagnosticReport(
        "helix-" + UUID.randomUUID(),
        generatedAt,
        artifacts,
        findings,
        timeline == null ? List.of() : timeline,
        correlationResult == null
            ? CorrelationSummary.empty()
            : correlationResult.summary(),
        JvmProfile.unknown(),
        null);
  }

  private static String fallbackArtifactId(Path file) {
    String fileName = file.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "-");
    return "artifact-" + fileName;
  }
}
