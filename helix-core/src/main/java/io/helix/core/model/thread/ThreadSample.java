package io.helix.core.model.thread;

import io.helix.core.model.StackFrame;
import java.util.List;

public record ThreadSample(
    String name,
    String javaThreadId,
    String osThreadId,
    JavaThreadState state,
    List<StackFrame> stackTrace,
    List<ThreadLock> heldLocks,
    ThreadLock waitingToLock,
    ThreadLock waitingOnLock,
    long cpuTimeMillis,
    String rawHeader
) {
  public ThreadSample {
    name = name == null || name.isBlank() ? "<unnamed-thread>" : name;
    javaThreadId = javaThreadId == null ? "" : javaThreadId;
    osThreadId = osThreadId == null ? "" : osThreadId;
    state = state == null ? JavaThreadState.UNKNOWN : state;
    stackTrace = stackTrace == null ? List.of() : List.copyOf(stackTrace);
    heldLocks = heldLocks == null ? List.of() : List.copyOf(heldLocks);
    rawHeader = rawHeader == null ? "" : rawHeader;
  }

  public ThreadLock waitingLock() {
    return waitingToLock != null ? waitingToLock : waitingOnLock;
  }
}
