package io.helix.core.model.thread;

public record ThreadLock(
    String identity,
    String className,
    String ownerThreadName,
    String source
) {
  public ThreadLock {
    identity = identity == null || identity.isBlank() ? "<unknown-lock>" : identity;
    className = className == null ? "" : className;
    ownerThreadName = ownerThreadName == null ? "" : ownerThreadName;
    source = source == null ? "" : source;
  }

  public String displayName() {
    if (className.isBlank()) {
      return identity;
    }
    return identity + " (" + className + ")";
  }
}
