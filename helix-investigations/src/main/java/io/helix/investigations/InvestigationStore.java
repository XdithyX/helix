package io.helix.investigations;

import java.util.List;
import java.util.Optional;

/**
 * Read/write contract for a local investigation workspace.
 *
 * <p>Implementations must be thread-safe for concurrent reads of different IDs.
 * Write operations (save) need not support concurrent writes for the same ID.
 */
public interface InvestigationStore {

  /**
   * Persist an investigation atomically. The stored investigation is only
   * visible to {@link #find} and {@link #list} after this method returns
   * successfully. An interrupted or failed save must never produce a queryable
   * READY record.
   */
  InvestigationSummary save(StoredInvestigation investigation);

  /**
   * Save a FAILED investigation summary (no evidence context).
   * Used when import fails; the failure reason is preserved for CLI inspection.
   */
  InvestigationSummary saveFailed(InvestigationSummary failedSummary);

  /**
   * Find a stored investigation by ID. Returns {@link Optional#empty()} for
   * unknown, deleted, or FAILED IDs (those have no queryable evidence context).
   */
  Optional<StoredInvestigation> find(InvestigationId id);

  /**
   * Return lightweight summaries for all persisted investigations in this
   * workspace, including FAILED ones. Does not load evidence into memory.
   */
  List<InvestigationSummary> list();
}
