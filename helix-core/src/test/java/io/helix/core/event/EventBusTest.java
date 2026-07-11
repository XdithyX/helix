package io.helix.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventBusTest {
  @Test
  void dispatchesEventsToMatchingSubscribers() {
    EventBus eventBus = new EventBus();
    List<String> received = new ArrayList<>();

    eventBus.subscribe(CharSequence.class, value -> received.add(value.toString()));
    eventBus.publish("gc-pause");

    assertThat(received).containsExactly("gc-pause");
  }

  @Test
  void subscriptionCanBeClosed() {
    EventBus eventBus = new EventBus();
    List<String> received = new ArrayList<>();

    EventBus.Subscription subscription = eventBus.subscribe(String.class, received::add);
    subscription.close();
    eventBus.publish("ignored");

    assertThat(received).isEmpty();
  }

  @Test
  void reportsSubscriberFailuresAfterDispatchAttempt() {
    EventBus eventBus = new EventBus();
    eventBus.subscribe(String.class, ignored -> {
      throw new IllegalStateException("boom");
    });

    assertThatThrownBy(() -> eventBus.publish("event"))
        .isInstanceOf(EventBusDispatchException.class)
        .hasMessageContaining("1 subscriber");
  }
}
