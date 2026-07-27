package io.helix.agent.core.investigation;

import java.time.Instant;

public record EvidenceReference(
    String artifactId,
    Instant timestamp,
    String reference
) {
  public EvidenceReference {
    artifactId = artifactId == null ? "" : artifactId;
    reference = reference == null ? "" : reference;
  }
}
