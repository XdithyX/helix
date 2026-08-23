package io.helix.investigations;

public enum InvestigationStatus {
  /** Import is running. Evidence is not yet queryable. */
  IMPORTING,
  /** Evidence is saved and queryable. */
  READY,
  /** Import failed. The failure reason is in the manifest. */
  FAILED
}
