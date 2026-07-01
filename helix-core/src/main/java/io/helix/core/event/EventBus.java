package io.helix.core.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class EventBus implements AutoCloseable {
  private final ConcurrentMap<Class<?>, CopyOnWriteArrayList<Consumer<Object>>> subscribers =
      new ConcurrentHashMap<>();

  public <T> Subscription subscribe(Class<T> eventType, Consumer<? super T> consumer) {
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(consumer, "consumer");

    Consumer<Object> wrapped = event -> consumer.accept(eventType.cast(event));
    subscribers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(wrapped);

    return () -> {
      CopyOnWriteArrayList<Consumer<Object>> listeners = subscribers.get(eventType);
      if (listeners != null) {
        listeners.remove(wrapped);
        if (listeners.isEmpty()) {
          subscribers.remove(eventType, listeners);
        }
      }
    };
  }

  public void publish(Object event) {
    Objects.requireNonNull(event, "event");
    List<RuntimeException> failures = new ArrayList<>();

    subscribers.forEach((eventType, listeners) -> {
      if (eventType.isInstance(event)) {
        for (Consumer<Object> listener : listeners) {
          try {
            listener.accept(event);
          } catch (RuntimeException e) {
            failures.add(e);
          }
        }
      }
    });

    if (!failures.isEmpty()) {
      throw new EventBusDispatchException(event, failures);
    }
  }

  @Override
  public void close() {
    subscribers.clear();
  }

  @FunctionalInterface
  public interface Subscription extends AutoCloseable {
    @Override
    void close();
  }
}
