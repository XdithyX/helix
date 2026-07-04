package io.helix.cli;

import io.helix.core.model.ArtifactType;
import io.helix.core.spi.Analyzer;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

final class AnalyzerRegistry {
  private final List<Analyzer> analyzers;

  private AnalyzerRegistry(List<Analyzer> analyzers) {
    this.analyzers = List.copyOf(analyzers);
  }

  static AnalyzerRegistry load() {
    List<Analyzer> loaded = ServiceLoader.load(Analyzer.class).stream()
        .map(ServiceLoader.Provider::get)
        .sorted(Comparator.comparing(Analyzer::analyzerId))
        .toList();
    return new AnalyzerRegistry(loaded);
  }

  List<Analyzer> supporting(ArtifactType type) {
    return analyzers.stream()
        .filter(analyzer -> analyzer.supportedTypes().contains(type))
        .toList();
  }
}
