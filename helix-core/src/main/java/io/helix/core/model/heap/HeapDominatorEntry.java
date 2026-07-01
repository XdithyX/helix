package io.helix.core.model.heap;

import java.util.List;

public record HeapDominatorEntry(
    String label,
    String className,
    String classLoaderName,
    long objectCount,
    long retainedBytes,
    double heapPercent,
    List<String> retentionPath
) {
  public HeapDominatorEntry {
    label = label == null || label.isBlank() ? className : label;
    if (className == null || className.isBlank()) {
      throw new IllegalArgumentException("className must not be blank");
    }
    classLoaderName = classLoaderName == null || classLoaderName.isBlank()
        ? "<unknown classloader>"
        : classLoaderName;
    if (objectCount < 0) {
      throw new IllegalArgumentException("objectCount must be non-negative");
    }
    if (retainedBytes < 0) {
      throw new IllegalArgumentException("retainedBytes must be non-negative");
    }
    retentionPath = retentionPath == null ? List.of() : List.copyOf(retentionPath);
  }
}
