package io.helix.core.model.heap;

public record HeapClassHistogramEntry(
    String className,
    String classLoaderName,
    long instanceCount,
    long shallowBytes,
    long retainedBytes
) {
  public HeapClassHistogramEntry {
    if (className == null || className.isBlank()) {
      throw new IllegalArgumentException("className must not be blank");
    }
    classLoaderName = classLoaderName == null || classLoaderName.isBlank()
        ? "<unknown classloader>"
        : classLoaderName;
    if (instanceCount < 0) {
      throw new IllegalArgumentException("instanceCount must be non-negative");
    }
    if (shallowBytes < 0) {
      throw new IllegalArgumentException("shallowBytes must be non-negative");
    }
    if (retainedBytes < 0) {
      throw new IllegalArgumentException("retainedBytes must be non-negative");
    }
  }
}
