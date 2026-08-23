package io.helix.investigations;

import io.helix.core.model.ArtifactType;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Lightweight metadata record stored in {@code manifest.json}.
 * Never contains raw artifact bytes or full evidence.
 */
public record InvestigationSummary(
    InvestigationId id,
    String name,
    InvestigationStatus status,
    Instant createdAt,
    List<ArtifactType> artifactTypes,
    boolean correlate,
    String failureReason
) {
  public InvestigationSummary {
    id = Objects.requireNonNull(id, "id");
    name = name == null ? "" : name.trim();
    status = Objects.requireNonNull(status, "status");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    artifactTypes = artifactTypes == null ? List.of() : List.copyOf(artifactTypes);
    failureReason = failureReason == null ? "" : failureReason;
  }

  /** Convenience constructor for a READY investigation (no failure reason). */
  public static InvestigationSummary ready(
      InvestigationId id,
      String name,
      Instant createdAt,
      List<ArtifactType> artifactTypes,
      boolean correlate) {
    return new InvestigationSummary(id, name, InvestigationStatus.READY, createdAt, artifactTypes, correlate, null);
  }

  /** Convenience constructor for a FAILED investigation. */
  public static InvestigationSummary failed(
      InvestigationId id,
      String name,
      Instant createdAt,
      List<ArtifactType> artifactTypes,
      boolean correlate,
      String failureReason) {
    return new InvestigationSummary(id, name, InvestigationStatus.FAILED, createdAt, artifactTypes, correlate, failureReason);
  }
}
