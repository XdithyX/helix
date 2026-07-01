package io.helix.core.model;

import java.util.Map;
import java.util.Objects;

public record StackFrameAnnotation(
    StackFrame frame,
    String frameworkName,
    String description,
    Map<String, Object> attributes
) {
  public StackFrameAnnotation {
    frame = Objects.requireNonNull(frame, "frame");
    if (frameworkName == null || frameworkName.isBlank()) {
      throw new IllegalArgumentException("frameworkName must not be blank");
    }
    description = Objects.requireNonNullElse(description, "");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
