package io.helix.analyzer.crash;

import io.helix.core.model.ArtifactType;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.Remediation;
import io.helix.core.model.RemediationType;
import io.helix.core.model.crash.CrashLogData;
import io.helix.core.model.crash.ProblematicFrame;
import io.helix.core.spi.AnalysisContext;
import io.helix.core.spi.Analyzer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

public final class CrashAnalyzer implements Analyzer {
  static final String CRASH_DATA_KEY = "crashLog";

  @Override
  public List<Finding> analyze(ParsedArtifact artifact, AnalysisContext ctx) {
    Object data = artifact.data().get(CRASH_DATA_KEY);
    if (!(data instanceof CrashLogData crashLog)) {
      return List.of();
    }

    List<Finding> findings = new ArrayList<>();
    addClassificationFinding(artifact, crashLog, findings);
    addKnownJvmBugFinding(artifact, crashLog, findings);
    addNativeLibraryFinding(artifact, crashLog, findings);
    addStackCorruptionFinding(artifact, crashLog, findings);
    addEnvironmentFinding(artifact, crashLog, findings);
    return findings;
  }

  @Override
  public Set<ArtifactType> supportedTypes() {
    return Set.of(ArtifactType.CRASH_LOG);
  }

  @Override
  public String analyzerId() {
    return "helix-analyzer-crash";
  }

  private static void addClassificationFinding(
      ParsedArtifact artifact,
      CrashLogData data,
      List<Finding> findings) {
    CrashClassification classification = classify(data);
    findings.add(new Finding(
        artifact.artifact().id() + "-crash-classification",
        classification.severity(),
        FindingCategory.NATIVE_CRASH,
        "JVM crash classification",
        classification.explanation(),
        classification.confidence(),
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            classification.remediation(),
            classification.remediationType(),
            classification.suggestion(),
            "Crash classification combines the signal and problematic native frame.")),
        mapOf(
            "category", classification.category(),
            "signal", data.signal(),
            "exception", data.exceptionCode(),
            "problematicFrame", data.problematicFrame())));
  }

  private static void addKnownJvmBugFinding(
      ParsedArtifact artifact,
      CrashLogData data,
      List<Finding> findings) {
    Optional<CrashBugSignature> match = matchingBugSignature(data);
    if (match.isEmpty()) {
      return;
    }
    CrashBugSignature signature = match.get();
    findings.add(new Finding(
        artifact.artifact().id() + "-known-jvm-bug",
        FindingSeverity.MEDIUM,
        FindingCategory.NATIVE_CRASH,
        "Crash signature matches embedded JVM bug data",
        signature.explanation(),
        0.62,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Upgrade the JVM before deeper application debugging.",
            RemediationType.JVM_FLAG,
            "Move to the latest patch release for this major JDK line.",
            "The embedded bug database is intentionally small in Phase 6 but preserves the extension point.")),
        mapOf(
            "bugId", signature.bugId(),
            "affectedVersions", signature.affectedVersions(),
            "fixVersion", signature.fixVersion())));
  }

  private static void addNativeLibraryFinding(
      ParsedArtifact artifact,
      CrashLogData data,
      List<Finding> findings) {
    ProblematicFrame frame = data.problematicFrame();
    if (frame.library().isBlank()) {
      return;
    }
    boolean applicationLibrary = isApplicationLibrary(frame.library());
    findings.add(new Finding(
        artifact.artifact().id() + "-native-library",
        applicationLibrary ? FindingSeverity.HIGH : FindingSeverity.INFO,
        FindingCategory.NATIVE_CRASH,
        applicationLibrary ? "Application JNI library in crash frame" : "Native library in crash frame",
        frame.library() + " is the library in the problematic frame"
            + (applicationLibrary ? " and does not look like a JVM or system library." : "."),
        0.80,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            applicationLibrary
                ? "Audit JNI/native memory ownership around the crashed symbol."
                : "Use the library identity to choose the next owner for investigation.",
            RemediationType.CODE_CHANGE,
            "",
            "The library in the problematic frame is often the most actionable crash owner.")),
        mapOf(
            "library", frame.library(),
            "symbol", frame.symbol(),
            "applicationLibrary", applicationLibrary)));
  }

  private static void addStackCorruptionFinding(
      ParsedArtifact artifact,
      CrashLogData data,
      List<Finding> findings) {
    List<String> suspicious = new ArrayList<>();
    for (Map.Entry<String, String> entry : data.registers().entrySet()) {
      String value = entry.getValue().toLowerCase(Locale.ROOT).replace("`", "");
      if (value.equals("0x0")
          || value.equals("0")
          || value.contains("deadbeef")
          || value.contains("baadf00d")
          || value.contains("cccccccc")) {
        suspicious.add(entry.getKey() + "=" + entry.getValue());
      }
    }
    long missingFrames = data.stackFrames().stream()
        .filter(frame -> frame.raw().contains("???") || frame.library().isBlank())
        .count();
    if (suspicious.isEmpty() && missingFrames == 0) {
      return;
    }
    findings.add(new Finding(
        artifact.artifact().id() + "-stack-corruption",
        FindingSeverity.HIGH,
        FindingCategory.NATIVE_CRASH,
        "Possible native stack corruption",
        "The register dump or native frames contain suspicious values: "
            + String.join(", ", suspicious) + (missingFrames > 0 ? "; missing frames=" + missingFrames : "") + ".",
        0.70,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Inspect native code for buffer overruns, use-after-free, or corrupted frame pointers.",
            RemediationType.CODE_CHANGE,
            "Reproduce with ASAN or JVM crash diagnostics enabled when possible.",
            "Impossible register values are a common native-memory corruption clue.")),
        mapOf("registers", suspicious, "missingFrames", missingFrames)));
  }

  private static void addEnvironmentFinding(
      ParsedArtifact artifact,
      CrashLogData data,
      List<Finding> findings) {
    String vm = data.jvmVersion().toLowerCase(Locale.ROOT);
    String os = data.environment().getOrDefault("OS", "").toLowerCase(Locale.ROOT);
    String cpu = data.environment().getOrDefault("CPU", "").toLowerCase(Locale.ROOT);
    boolean jvm32On64 = vm.contains("32-bit") && (os.contains("x86_64") || cpu.contains("64"));
    boolean numa = cpu.contains("numa") && data.vmFlags().stream().noneMatch(flag -> flag.contains("UseNUMA"));
    if (!jvm32On64 && !numa) {
      return;
    }
    findings.add(new Finding(
        artifact.artifact().id() + "-crash-environment",
        FindingSeverity.MEDIUM,
        FindingCategory.CONFIGURATION,
        "Crash-prone JVM environment mismatch",
        jvm32On64
            ? "The crash log suggests a 32-bit JVM running on a 64-bit operating system."
            : "The crash log mentions NUMA topology without matching JVM NUMA configuration.",
        0.64,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Align JVM architecture and host topology with the deployment target.",
            RemediationType.CONFIG_CHANGE,
            jvm32On64 ? "Use a 64-bit JVM for large heaps." : "Review -XX:+UseNUMA for this workload.",
            "Environment mismatches are rarely the direct bug, but they reduce crash resilience.")),
        mapOf("environment", data.environment(), "vmFlags", data.vmFlags())));
  }

  private static CrashClassification classify(CrashLogData data) {
    String signal = data.signal();
    String library = data.problematicFrame().library();
    String symbol = data.problematicFrame().symbol();
    if (data.oomInHeader()) {
      return new CrashClassification(
          "java-heap-exhaustion",
          FindingSeverity.HIGH,
          0.85,
          "OutOfMemoryError appears in the crash header, indicating Java heap exhaustion before or during the crash.",
          "Analyze the heap pressure that preceded the fatal error.",
          RemediationType.CONFIG_CHANGE,
          "Capture a heap dump with -XX:+HeapDumpOnOutOfMemoryError.");
    }
    if (containsIgnoreCase(signal, "EXCEPTION_ACCESS_VIOLATION")) {
      return new CrashClassification(
          "windows-null-dereference",
          FindingSeverity.HIGH,
          0.78,
          "Windows access violation usually indicates a null or invalid native pointer dereference.",
          "Inspect native or JNI code near the problematic frame.",
          RemediationType.CODE_CHANGE,
          "");
    }
    if (containsIgnoreCase(signal, "SIGBUS")) {
      return new CrashClassification(
          "memory-mapped-file-issue",
          FindingSeverity.HIGH,
          0.82,
          "SIGBUS commonly points to invalid memory-mapped file access or truncated mapped files.",
          "Check memory-mapped files, disk pressure, and file truncation.",
          RemediationType.CONFIG_CHANGE,
          "");
    }
    if (containsIgnoreCase(signal, "SIGABRT")) {
      return new CrashClassification(
          "assertion-or-abort",
          FindingSeverity.HIGH,
          0.76,
          "SIGABRT indicates abort() or an assertion failure in native/JVM code.",
          "Inspect the fatal error message and native library owner.",
          RemediationType.CODE_CHANGE,
          "");
    }
    if (containsIgnoreCase(signal, "SIGSEGV") && containsIgnoreCase(library, "libjvm")) {
      return new CrashClassification(
          "jvm-bug-or-flag-issue",
          FindingSeverity.HIGH,
          0.74,
          "SIGSEGV occurred in libjvm, which points to a JVM bug, unsafe JVM flag, or VM-internal crash path.",
          "Upgrade to the latest JVM patch and review non-default VM flags.",
          RemediationType.JVM_FLAG,
          "Temporarily remove experimental JVM flags while reproducing.");
    }
    if (containsIgnoreCase(signal, "SIGSEGV") && containsIgnoreCase(library, "libgcc")) {
      return new CrashClassification(
          "stack-overflow-or-corrupted-stack",
          FindingSeverity.HIGH,
          0.70,
          "SIGSEGV in libgcc often points to stack overflow or corrupted native stack unwinding.",
          "Inspect stack size, recursion, and native frame integrity.",
          RemediationType.CONFIG_CHANGE,
          "Review -Xss only after checking for runaway recursion.");
    }
    if (containsIgnoreCase(signal, "SIGSEGV")) {
      return new CrashClassification(
          "jni-memory-corruption",
          FindingSeverity.HIGH,
          0.78,
          "SIGSEGV occurred in " + (library.isBlank() ? "an application/native frame" : library)
              + ", which commonly indicates JNI or native memory corruption.",
          "Audit native memory access around " + (symbol.isBlank() ? "the problematic frame" : symbol) + ".",
          RemediationType.CODE_CHANGE,
          "");
    }
    return new CrashClassification(
        "unclassified-native-crash",
        FindingSeverity.MEDIUM,
        0.50,
        "The crash signal could not be mapped to a more specific Phase 6 category.",
        "Inspect the problematic frame and native stack manually.",
        RemediationType.CODE_CHANGE,
        "");
  }

  private static boolean isApplicationLibrary(String library) {
    String lower = library.toLowerCase(Locale.ROOT);
    return !lower.contains("libjvm")
        && !lower.contains("libjava")
        && !lower.contains("libc.")
        && !lower.contains("libsystem")
        && !lower.contains("kernel")
        && !lower.contains("ntdll")
        && !lower.contains("libgcc")
        && !lower.contains("pthread");
  }

  private static Optional<CrashBugSignature> matchingBugSignature(CrashLogData data) {
    return bugSignatures().stream()
        .filter(signature -> signature.requiredSignal().isBlank()
            || containsIgnoreCase(data.signal(), signature.requiredSignal()))
        .filter(signature -> signature.requiredLibrary().isBlank()
            || containsIgnoreCase(data.problematicFrame().library(), signature.requiredLibrary()))
        .filter(signature -> signature.affectedVersions().stream().anyMatch(data.jvmVersion()::contains))
        .findFirst();
  }

  private static List<CrashBugSignature> bugSignatures() {
    List<CrashBugSignature> signatures = new ArrayList<>(List.of(new CrashBugSignature(
        "JDK-8279077",
        List.of("17.0.1", "17.0.2"),
        "17.0.3+",
        "SIGSEGV",
        "libjvm",
        "The crash is in libjvm on a JVM version with known HotSpot crash fixes after 17.0.2.")));
    ServiceLoader.load(CrashBugSignatureProvider.class).forEach(provider -> signatures.addAll(provider.signatures()));
    return signatures;
  }

  private static boolean containsIgnoreCase(String text, String pattern) {
    return text != null && text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
  }

  private static Map<String, Object> mapOf(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      Object value = values[i + 1];
      if (value != null) {
        result.put((String) values[i], value);
      }
    }
    return result;
  }

  private record CrashClassification(
      String category,
      FindingSeverity severity,
      double confidence,
      String explanation,
      String remediation,
      RemediationType remediationType,
      String suggestion) {}
}
