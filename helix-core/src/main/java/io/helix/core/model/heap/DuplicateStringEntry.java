package io.helix.core.model.heap;

public record DuplicateStringEntry(
    String valuePreview,
    long instances,
    long wastedBytes
) {
  public DuplicateStringEntry {
    valuePreview = valuePreview == null ? "" : valuePreview;
    if (instances < 0) {
      throw new IllegalArgumentException("instances must be non-negative");
    }
    if (wastedBytes < 0) {
      throw new IllegalArgumentException("wastedBytes must be non-negative");
    }
  }
}
