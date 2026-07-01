package io.helix.core.model.heap;

import java.util.List;

public record ByteArrayLeakSuspect(
    String label,
    long arrayCount,
    long totalBytes,
    double heapPercent,
    List<String> retentionPath
) {
  public ByteArrayLeakSuspect {
    label = label == null || label.isBlank() ? "byte[]" : label;
    if (arrayCount < 0) {
      throw new IllegalArgumentException("arrayCount must be non-negative");
    }
    if (totalBytes < 0) {
      throw new IllegalArgumentException("totalBytes must be non-negative");
    }
    retentionPath = retentionPath == null ? List.of() : List.copyOf(retentionPath);
  }
}
