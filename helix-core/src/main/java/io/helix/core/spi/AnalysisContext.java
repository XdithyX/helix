package io.helix.core.spi;

import io.helix.core.progress.ProgressMonitor;
import java.util.Map;

public record AnalysisContext(
    ProgressMonitor progressMonitor,
    Map<String, Object> options
) {
  public AnalysisContext {
    progressMonitor = progressMonitor == null ? ProgressMonitor.noop() : progressMonitor;
    options = options == null ? Map.of() : Map.copyOf(options);
  }

  public static AnalysisContext defaults() {
    return new AnalysisContext(ProgressMonitor.noop(), Map.of());
  }
}
