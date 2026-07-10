package io.helix.analyzer.crash;

import java.util.List;

public record CrashBugSignature(
    String bugId,
    List<String> affectedVersions,
    String fixVersion,
    String requiredSignal,
    String requiredLibrary,
    String explanation
) {
  public CrashBugSignature {
    bugId = bugId == null || bugId.isBlank() ? "<unknown-bug>" : bugId;
    affectedVersions = affectedVersions == null ? List.of() : List.copyOf(affectedVersions);
    fixVersion = fixVersion == null ? "" : fixVersion;
    requiredSignal = requiredSignal == null ? "" : requiredSignal;
    requiredLibrary = requiredLibrary == null ? "" : requiredLibrary;
    explanation = explanation == null ? "" : explanation;
  }
}
