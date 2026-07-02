package io.helix.core.spi;

import io.helix.core.progress.ProgressMonitor;
import java.util.Map;

public record ParseContext(
    ProgressMonitor progressMonitor,
    Map<String, String> metadata
) {
  public ParseContext {
    progressMonitor = progressMonitor == null ? ProgressMonitor.noop() : progressMonitor;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  public static ParseContext defaults() {
    return new ParseContext(ProgressMonitor.noop(), Map.of());
  }
}
