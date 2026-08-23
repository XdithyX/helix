package io.helix.investigations;

import io.helix.agent.core.investigation.InvestigationContext;
import java.util.Objects;

/**
 * A ready investigation: lightweight summary metadata paired with the full
 * {@link InvestigationContext} reconstructed from the persisted evidence snapshot.
 * Only ever created for {@link InvestigationStatus#READY} investigations.
 */
public record StoredInvestigation(
    InvestigationSummary summary,
    InvestigationContext context
) {
  public StoredInvestigation {
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(context, "context");
    if (summary.status() != InvestigationStatus.READY) {
      throw new IllegalArgumentException(
          "StoredInvestigation requires READY status; got " + summary.status());
    }
  }
}
