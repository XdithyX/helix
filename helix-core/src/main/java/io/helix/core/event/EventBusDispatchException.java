package io.helix.core.event;

import java.util.List;

public final class EventBusDispatchException extends RuntimeException {
  private final Object event;
  private final List<RuntimeException> failures;

  public EventBusDispatchException(Object event, List<RuntimeException> failures) {
    super("Failed to dispatch event to " + failures.size() + " subscriber(s)");
    this.event = event;
    this.failures = List.copyOf(failures);
    this.failures.forEach(this::addSuppressed);
  }

  public Object event() {
    return event;
  }

  public List<RuntimeException> failures() {
    return failures;
  }
}
