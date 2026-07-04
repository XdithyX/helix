package io.helix.cli;

import java.util.List;

final class BuildInfo {
  static final String FALLBACK_VERSION = "0.1.0-SNAPSHOT";

  private BuildInfo() {}

  static String version() {
    String implementationVersion = HelixMain.class.getPackage().getImplementationVersion();
    return implementationVersion == null ? FALLBACK_VERSION : implementationVersion;
  }

  static List<String> components() {
    return List.of(
        "helix-core",
        "helix-cli",
        "helix-reporter-text",
        "helix-reporter-json",
        "helix-parser-hprof",
        "helix-analyzer-memory");
  }
}
