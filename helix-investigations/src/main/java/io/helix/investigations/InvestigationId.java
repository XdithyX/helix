package io.helix.investigations;

import java.util.Objects;

/**
 * Typed, opaque investigation identifier. Format is validated on construction;
 * the value must be non-blank and contain only alphanumeric characters, hyphens,
 * and underscores so it is always safe to use as a directory name.
 */
public record InvestigationId(String value) {

  private static final java.util.regex.Pattern SAFE = java.util.regex.Pattern.compile("[A-Za-z0-9_-]+");

  public InvestigationId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("Investigation ID must not be blank.");
    }
    if (!SAFE.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "Investigation ID contains unsafe characters: " + value);
    }
  }

  /** Generate a time-ordered ID in the format {@code inv_<ulid-like>}. */
  public static InvestigationId generate() {
    // Use UUID-based monotonic ID; ULID library is not a dependency.
    String uuid = java.util.UUID.randomUUID().toString().replace("-", "");
    return new InvestigationId("inv_" + uuid.substring(0, 26));
  }

  @Override
  public String toString() {
    return value;
  }
}
