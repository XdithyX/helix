package io.helix.core.model.gc;

import java.util.List;
import java.util.Map;

public record GcLogData(
    List<GcEvent> events,
    GcLogFormat format,
    Map<String, Object> metadata
) {
  public GcLogData {
    events = events == null ? List.of() : List.copyOf(events);
    format = format == null ? inferFormat(events) : format;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  public List<GcEvent> pauseEvents() {
    return events.stream()
        .filter(GcEvent::pause)
        .toList();
  }

  private static GcLogFormat inferFormat(List<GcEvent> events) {
    if (events == null || events.isEmpty()) {
      return GcLogFormat.UNKNOWN;
    }
    Object first = events.getFirst().attributes().get("format");
    if (!(first instanceof GcLogFormat firstFormat)) {
      return GcLogFormat.UNKNOWN;
    }
    boolean same = events.stream().allMatch(event -> event.attributes().get("format") == firstFormat);
    return same ? firstFormat : GcLogFormat.MIXED;
  }
}
