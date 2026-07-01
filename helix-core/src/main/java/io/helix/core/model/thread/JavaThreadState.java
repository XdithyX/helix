package io.helix.core.model.thread;

public enum JavaThreadState {
  NEW,
  RUNNABLE,
  BLOCKED,
  WAITING,
  TIMED_WAITING,
  TERMINATED,
  UNKNOWN;

  public boolean isActive() {
    return this == RUNNABLE || this == BLOCKED;
  }
}
