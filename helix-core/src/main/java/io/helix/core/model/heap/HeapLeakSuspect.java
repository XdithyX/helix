package io.helix.core.model.heap;

import java.util.List;

public record HeapLeakSuspect(
    String className,
    String classLoaderName,
    long retainedBytes,
    double heapPercent,
    List<String> retentionPath
) {
  public HeapLeakSuspect {
    if (className == null || className.isBlank()) {
      throw new IllegalArgumentException("className must not be blank");
    }
    classLoaderName = classLoaderName == null || classLoaderName.isBlank()
        ? "<unknown classloader>"
        : classLoaderName;
    if (retainedBytes < 0) {
      throw new IllegalArgumentException("retainedBytes must be non-negative");
    }
    retentionPath = retentionPath == null ? List.of() : List.copyOf(retentionPath);
  }
}
