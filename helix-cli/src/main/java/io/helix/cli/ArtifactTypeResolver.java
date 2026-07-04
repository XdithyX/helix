package io.helix.cli;

import io.helix.core.model.ArtifactType;
import java.nio.file.Path;
import java.util.Locale;

final class ArtifactTypeResolver {
  private ArtifactTypeResolver() {}

  static ArtifactType resolve(Path path, String hint) {
    if (hint != null && !hint.isBlank()) {
      return resolveHint(hint);
    }
    String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (fileName.endsWith(".hprof")) {
      return ArtifactType.HEAP_DUMP;
    }
    if (fileName.endsWith(".jfr")) {
      return ArtifactType.JFR;
    }
    if (fileName.startsWith("hs_err_pid") && fileName.endsWith(".log")) {
      return ArtifactType.CRASH_LOG;
    }
    if (fileName.endsWith(".tdump")
        || fileName.endsWith(".threaddump")
        || fileName.endsWith(".thread")
        || fileName.endsWith(".txt")) {
      return ArtifactType.THREAD_DUMP;
    }
    if (fileName.endsWith(".gc")
        || fileName.endsWith(".gclog")
        || fileName.endsWith(".log")
        || fileName.endsWith(".xml")) {
      return ArtifactType.GC_LOG;
    }
    throw new IllegalArgumentException(
        "Could not infer artifact type for " + path + "; pass --type heap|thread|gclog|jfr|crash");
  }

  private static ArtifactType resolveHint(String hint) {
    return switch (hint.toLowerCase(Locale.ROOT)) {
      case "heap", "hprof", "heap_dump" -> ArtifactType.HEAP_DUMP;
      case "thread", "threads", "threaddump", "thread_dump" -> ArtifactType.THREAD_DUMP;
      case "gc", "gclog", "gc_log" -> ArtifactType.GC_LOG;
      case "jfr" -> ArtifactType.JFR;
      case "crash", "crashlog", "crash_log", "hs_err" -> ArtifactType.CRASH_LOG;
      default -> throw new IllegalArgumentException(
          "Unsupported artifact type hint: " + hint + " (expected heap|thread|gclog|jfr|crash)");
    };
  }
}
