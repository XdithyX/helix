package io.helix.investigations;

/** Thrown when a workspace store operation fails at the I/O level. */
public final class InvestigationStoreException extends RuntimeException {
  public InvestigationStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
