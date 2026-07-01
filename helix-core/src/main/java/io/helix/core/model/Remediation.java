package io.helix.core.model;

import java.util.Objects;

public record Remediation(
    String description,
    RemediationType type,
    String suggestion,
    String rationale
) {
  public Remediation {
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("description must not be blank");
    }
    type = Objects.requireNonNull(type, "type");
    suggestion = Objects.requireNonNullElse(suggestion, "");
    rationale = Objects.requireNonNullElse(rationale, "");
  }
}
