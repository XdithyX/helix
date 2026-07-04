package io.helix.cli;

import io.helix.core.spi.Reporter;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

final class ReporterRegistry {
  private final Map<String, Reporter> reporters;

  private ReporterRegistry(Map<String, Reporter> reporters) {
    this.reporters = Map.copyOf(reporters);
  }

  static ReporterRegistry load() {
    Map<String, Reporter> loaded = new LinkedHashMap<>();
    ServiceLoader.load(Reporter.class).stream()
        .map(ServiceLoader.Provider::get)
        .sorted(Comparator.comparing(Reporter::format))
        .forEach(reporter -> loaded.put(reporter.format().toLowerCase(Locale.ROOT), reporter));
    return new ReporterRegistry(loaded);
  }

  Optional<Reporter> find(String format) {
    if (format == null || format.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(reporters.get(format.toLowerCase(Locale.ROOT)));
  }

  Collection<String> formats() {
    return reporters.keySet();
  }
}
