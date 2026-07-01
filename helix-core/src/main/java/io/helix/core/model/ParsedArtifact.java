package io.helix.core.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ParsedArtifact(
    Artifact artifact,
    List<TimelineEvent> timelineEvents,
    Map<String, Object> data,
    List<String> warnings
) {
  public ParsedArtifact {
    artifact = Objects.requireNonNull(artifact, "artifact");
    timelineEvents = timelineEvents == null ? List.of() : List.copyOf(timelineEvents);
    data = data == null ? Map.of() : Map.copyOf(data);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
