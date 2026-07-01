package io.helix.core.model.heap;

import java.util.List;

public record DuplicateStringSummary(
    long duplicateValueCount,
    long duplicateInstanceCount,
    long wastedBytes,
    List<DuplicateStringEntry> topDuplicates
) {
  public DuplicateStringSummary {
    if (duplicateValueCount < 0) {
      throw new IllegalArgumentException("duplicateValueCount must be non-negative");
    }
    if (duplicateInstanceCount < 0) {
      throw new IllegalArgumentException("duplicateInstanceCount must be non-negative");
    }
    if (wastedBytes < 0) {
      throw new IllegalArgumentException("wastedBytes must be non-negative");
    }
    topDuplicates = topDuplicates == null ? List.of() : List.copyOf(topDuplicates);
  }

  public static DuplicateStringSummary empty() {
    return new DuplicateStringSummary(0, 0, 0, List.of());
  }
}
