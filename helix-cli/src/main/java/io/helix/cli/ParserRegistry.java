package io.helix.cli;

import io.helix.core.model.ArtifactType;
import io.helix.core.spi.ArtifactParser;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

final class ParserRegistry {
  private final List<ArtifactParser> parsers;

  private ParserRegistry(List<ArtifactParser> parsers) {
    this.parsers = List.copyOf(parsers);
  }

  static ParserRegistry load() {
    List<ArtifactParser> loaded = ServiceLoader.load(ArtifactParser.class).stream()
        .map(ServiceLoader.Provider::get)
        .sorted(Comparator.comparing(parser -> parser.artifactType().name()))
        .toList();
    return new ParserRegistry(loaded);
  }

  Optional<ArtifactParser> find(Path path, ArtifactType hint) {
    return parsers.stream()
        .filter(parser -> parser.canParse(path, hint))
        .findFirst();
  }
}
