package io.helix.cli;

import io.helix.core.spi.Correlator;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

final class CorrelatorRegistry {
  private final List<Correlator> correlators;

  private CorrelatorRegistry(List<Correlator> correlators) {
    this.correlators = List.copyOf(correlators);
  }

  static CorrelatorRegistry load() {
    List<Correlator> loaded = ServiceLoader.load(Correlator.class).stream()
        .map(ServiceLoader.Provider::get)
        .sorted(Comparator.comparing(correlator -> correlator.getClass().getName()))
        .toList();
    return new CorrelatorRegistry(loaded);
  }

  Optional<Correlator> first() {
    return correlators.stream().findFirst();
  }
}
