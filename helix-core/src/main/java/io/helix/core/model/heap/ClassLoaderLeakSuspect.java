package io.helix.core.model.heap;

import java.util.List;

public record ClassLoaderLeakSuspect(
    String classLoaderName,
    long retainedBytes,
    double heapPercent,
    long definedClassCount,
    List<String> retainedClasses
) {
  public ClassLoaderLeakSuspect {
    classLoaderName = classLoaderName == null || classLoaderName.isBlank()
        ? "<unknown classloader>"
        : classLoaderName;
    if (retainedBytes < 0) {
      throw new IllegalArgumentException("retainedBytes must be non-negative");
    }
    if (definedClassCount < 0) {
      throw new IllegalArgumentException("definedClassCount must be non-negative");
    }
    retainedClasses = retainedClasses == null ? List.of() : List.copyOf(retainedClasses);
  }
}
