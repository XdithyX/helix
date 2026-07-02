package io.helix.core.progress;

public interface ProgressMonitor {
  ProgressMonitor NOOP = new NoopProgressMonitor();

  static ProgressMonitor noop() {
    return NOOP;
  }

  default void beginTask(String name, long totalWork) {}

  default void worked(long work) {}

  default void message(String message) {}

  default void complete() {}

  default boolean isCancelled() {
    return false;
  }

  final class NoopProgressMonitor implements ProgressMonitor {
    private NoopProgressMonitor() {}
  }
}
