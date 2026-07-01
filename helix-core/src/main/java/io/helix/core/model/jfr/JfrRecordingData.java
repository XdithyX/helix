package io.helix.core.model.jfr;

import java.util.List;
import java.util.Map;

public record JfrRecordingData(
    List<JfrEvent> events,
    Map<String, Object> metadata
) {
  public JfrRecordingData {
    events = events == null ? List.of() : List.copyOf(events);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  public List<JfrEvent> eventsOfType(String eventType) {
    return events.stream()
        .filter(event -> event.eventType().equals(eventType))
        .toList();
  }
}
