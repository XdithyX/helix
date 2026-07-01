package io.helix.core.model;

import java.util.Map;

public record FrameworkContext(
    String frameworkName,
    String role,
    Map<String, Object> attributes
) {
  public FrameworkContext {
    if (frameworkName == null || frameworkName.isBlank()) {
      throw new IllegalArgumentException("frameworkName must not be blank");
    }
    role = role == null ? "" : role;
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
