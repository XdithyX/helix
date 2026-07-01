package io.helix.core.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record Artifact(
    String id,
    ArtifactType type,
    Instant capturedAt,
    Map<String, String> metadata,
    JvmFlavour jvmFlavour,
    Path sourcePath
) {
  public Artifact {
    id = requireText(id, "id");
    type = Objects.requireNonNull(type, "type");
    capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    jvmFlavour = jvmFlavour == null ? JvmFlavour.UNKNOWN : jvmFlavour;
    sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
