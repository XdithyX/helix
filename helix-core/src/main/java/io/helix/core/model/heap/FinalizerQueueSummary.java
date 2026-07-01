package io.helix.core.model.heap;

public record FinalizerQueueSummary(long pendingObjects) {
  public FinalizerQueueSummary {
    if (pendingObjects < 0) {
      throw new IllegalArgumentException("pendingObjects must be non-negative");
    }
  }

  public static FinalizerQueueSummary empty() {
    return new FinalizerQueueSummary(0);
  }
}
