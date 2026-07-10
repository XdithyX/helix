package io.helix.analyzer.memory;

import io.helix.core.model.ArtifactType;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.Remediation;
import io.helix.core.model.RemediationType;
import io.helix.core.model.heap.ByteArrayLeakSuspect;
import io.helix.core.model.heap.ClassLoaderLeakSuspect;
import io.helix.core.model.heap.DuplicateStringSummary;
import io.helix.core.model.heap.FinalizerQueueSummary;
import io.helix.core.model.heap.HeapDominatorEntry;
import io.helix.core.model.heap.HeapLeakSuspect;
import io.helix.core.model.heap.HeapSnapshotData;
import io.helix.core.spi.AnalysisContext;
import io.helix.core.spi.Analyzer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MemoryAnalyzer implements Analyzer {
  static final String HEAP_DATA_KEY = "heapSnapshot";
  private static final double DEFAULT_LEAK_THRESHOLD_PERCENT = 10.0;
  private static final int DEFAULT_MAX_DOMINATORS = 20;
  private static final long DEFAULT_FINALIZER_BACKLOG_THRESHOLD = 1_000;
  private static final double DEFAULT_BYTE_ARRAY_THRESHOLD_PERCENT = 10.0;
  private static final double DEFAULT_CLASSLOADER_THRESHOLD_PERCENT = 10.0;

  @Override
  public List<Finding> analyze(ParsedArtifact artifact, AnalysisContext ctx) {
    Object heapData = artifact.data().get(HEAP_DATA_KEY);
    if (!(heapData instanceof HeapSnapshotData snapshot)) {
      return List.of();
    }

    List<Finding> findings = new ArrayList<>();
    double leakThreshold = option(ctx, "heap.leakThresholdPercent", DEFAULT_LEAK_THRESHOLD_PERCENT);
    int maxDominators = (int) option(ctx, "heap.maxDominators", DEFAULT_MAX_DOMINATORS);

    addLeakSuspectFindings(artifact, snapshot, findings, leakThreshold);
    addDominatorFinding(artifact, snapshot, findings, maxDominators);
    addDuplicateStringFinding(artifact, snapshot, findings);
    addClassHistogramFinding(artifact, snapshot, findings);
    addClassLoaderLeakFindings(artifact, snapshot, findings);
    addFinalizerBacklogFinding(artifact, snapshot, findings);
    addByteArrayLeakFindings(artifact, snapshot, findings);

    return findings;
  }

  @Override
  public Set<ArtifactType> supportedTypes() {
    return Set.of(ArtifactType.HEAP_DUMP);
  }

  @Override
  public String analyzerId() {
    return "helix-analyzer-memory";
  }

  private static void addLeakSuspectFindings(
      ParsedArtifact artifact,
      HeapSnapshotData snapshot,
      List<Finding> findings,
      double leakThreshold) {
    int index = 1;
    for (HeapLeakSuspect suspect : snapshot.leakSuspects().stream()
        .filter(candidate -> candidate.heapPercent() >= leakThreshold)
        .sorted(Comparator.comparing(HeapLeakSuspect::retainedBytes).reversed())
        .toList()) {
      findings.add(new Finding(
          artifact.artifact().id() + "-memory-leak-" + index++,
          suspect.heapPercent() >= 25.0 ? FindingSeverity.HIGH : FindingSeverity.MEDIUM,
          FindingCategory.MEMORY,
          "Heap leak suspect: " + suspect.className(),
          leakExplanation(suspect),
          suspect.heapPercent() >= 25.0 ? 0.90 : 0.78,
          List.of(artifact.artifact().id()),
          List.of(),
          List.of(new Remediation(
              "Inspect the retaining references and object lifecycle for " + suspect.className() + ".",
              RemediationType.CODE_CHANGE,
              "Break the retention path or bound the cache/collection retaining this object graph.",
              "The object graph retains " + formatBytes(suspect.retainedBytes())
                  + ", which is above the configured leak threshold.")),
          mapOf(
              "retainedBytes", suspect.retainedBytes(),
              "heapPercent", suspect.heapPercent(),
              "classLoader", suspect.classLoaderName(),
              "retentionPath", suspect.retentionPath())));
    }
  }

  private static void addDominatorFinding(
      ParsedArtifact artifact,
      HeapSnapshotData snapshot,
      List<Finding> findings,
      int maxDominators) {
    List<HeapDominatorEntry> dominators = snapshot.dominators().stream()
        .sorted(Comparator.comparing(HeapDominatorEntry::retainedBytes).reversed())
        .limit(maxDominators)
        .toList();
    if (dominators.isEmpty()) {
      return;
    }

    findings.add(new Finding(
        artifact.artifact().id() + "-memory-dominators",
        FindingSeverity.INFO,
        FindingCategory.MEMORY,
        "Top heap dominators",
        "The largest dominator is " + dominators.getFirst().label() + ", retaining "
            + formatBytes(dominators.getFirst().retainedBytes()) + " ("
            + formatPercent(dominators.getFirst().heapPercent()) + " of heap).",
        0.70,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Review the largest dominator objects before tuning heap size.",
            RemediationType.CODE_CHANGE,
            "Start with the top retained object graph and verify whether it is expected to live for the JVM lifetime.",
            "Dominator retained size identifies which object graphs prevent the most memory from being reclaimed.")),
        mapOf("dominators", dominators)));
  }

  private static void addDuplicateStringFinding(
      ParsedArtifact artifact,
      HeapSnapshotData snapshot,
      List<Finding> findings) {
    DuplicateStringSummary duplicateStrings = snapshot.duplicateStrings();
    if (duplicateStrings.duplicateValueCount() == 0) {
      return;
    }

    findings.add(new Finding(
        artifact.artifact().id() + "-memory-duplicate-strings",
        duplicateStrings.wastedBytes() >= snapshot.totalHeapBytes() * 0.05
            ? FindingSeverity.MEDIUM
            : FindingSeverity.LOW,
        FindingCategory.MEMORY,
        "Duplicate String instances",
        duplicateStrings.duplicateInstanceCount() + " duplicate String instances waste approximately "
            + formatBytes(duplicateStrings.wastedBytes()) + " across "
            + duplicateStrings.duplicateValueCount() + " repeated values.",
        0.75,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Reduce repeated String allocation where the duplicated values are long-lived.",
            RemediationType.CODE_CHANGE,
            "Consider canonicalization for bounded domains or review cache keys and parser output.",
            "Duplicate long-lived strings increase retained heap without adding information.")),
        mapOf(
            "duplicateValueCount", duplicateStrings.duplicateValueCount(),
            "duplicateInstanceCount", duplicateStrings.duplicateInstanceCount(),
            "wastedBytes", duplicateStrings.wastedBytes(),
            "topDuplicates", duplicateStrings.topDuplicates())));
  }

  private static void addClassHistogramFinding(
      ParsedArtifact artifact,
      HeapSnapshotData snapshot,
      List<Finding> findings) {
    if (snapshot.classHistogram().isEmpty()) {
      return;
    }

    findings.add(new Finding(
        artifact.artifact().id() + "-memory-class-histogram",
        FindingSeverity.INFO,
        FindingCategory.MEMORY,
        "Class histogram",
        "The heap contains " + snapshot.totalObjects() + " objects across "
            + snapshot.totalClasses() + " classes. The largest shallow class is "
            + snapshot.classHistogram().getFirst().className() + ".",
        0.70,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Use the histogram to decide where detailed retained-size analysis should begin.",
            RemediationType.CODE_CHANGE,
            "",
            "Classes with high shallow size or instance count often explain allocation pressure.")),
        mapOf("topClasses", snapshot.classHistogram())));
  }

  private static void addClassLoaderLeakFindings(
      ParsedArtifact artifact,
      HeapSnapshotData snapshot,
      List<Finding> findings) {
    int index = 1;
    for (ClassLoaderLeakSuspect suspect : snapshot.classLoaderLeakSuspects().stream()
        .filter(candidate -> candidate.heapPercent() >= DEFAULT_CLASSLOADER_THRESHOLD_PERCENT)
        .sorted(Comparator.comparing(ClassLoaderLeakSuspect::retainedBytes).reversed())
        .toList()) {
      findings.add(new Finding(
          artifact.artifact().id() + "-memory-classloader-leak-" + index++,
          suspect.heapPercent() >= 25.0 ? FindingSeverity.HIGH : FindingSeverity.MEDIUM,
          FindingCategory.MEMORY,
          "Possible classloader leak: " + suspect.classLoaderName(),
          suspect.classLoaderName() + " retains " + formatBytes(suspect.retainedBytes())
              + " (" + formatPercent(suspect.heapPercent()) + " of heap) across "
              + suspect.definedClassCount() + " defined classes.",
          0.78,
          List.of(artifact.artifact().id()),
          List.of(),
          List.of(new Remediation(
              "Check whether an application, plugin, or webapp classloader should have been unloaded.",
              RemediationType.CODE_CHANGE,
              "Remove static references, thread locals, or running threads that retain the old classloader.",
              "Hot-deploy leaks commonly retain large graphs through stale classloaders.")),
          mapOf(
              "classLoader", suspect.classLoaderName(),
              "retainedBytes", suspect.retainedBytes(),
              "heapPercent", suspect.heapPercent(),
              "retainedClasses", suspect.retainedClasses())));
    }
  }

  private static void addFinalizerBacklogFinding(
      ParsedArtifact artifact,
      HeapSnapshotData snapshot,
      List<Finding> findings) {
    FinalizerQueueSummary queue = snapshot.finalizerQueue();
    if (queue.pendingObjects() <= DEFAULT_FINALIZER_BACKLOG_THRESHOLD) {
      return;
    }

    findings.add(new Finding(
        artifact.artifact().id() + "-memory-finalizer-backlog",
        FindingSeverity.HIGH,
        FindingCategory.MEMORY,
        "Finalizer queue backlog",
        "The finalizer queue has " + queue.pendingObjects()
            + " pending objects, above the Phase 2 threshold of "
            + DEFAULT_FINALIZER_BACKLOG_THRESHOLD + ".",
        0.82,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Remove reliance on finalization for high-volume resources.",
            RemediationType.CODE_CHANGE,
            "Use AutoCloseable/Cleaner patterns and close resources explicitly.",
            "A finalizer backlog delays reclamation and can amplify memory pressure.")),
        mapOf("pendingObjects", queue.pendingObjects())));
  }

  private static void addByteArrayLeakFindings(
      ParsedArtifact artifact,
      HeapSnapshotData snapshot,
      List<Finding> findings) {
    int index = 1;
    for (ByteArrayLeakSuspect suspect : snapshot.byteArrayLeakSuspects().stream()
        .filter(candidate -> candidate.heapPercent() >= DEFAULT_BYTE_ARRAY_THRESHOLD_PERCENT)
        .sorted(Comparator.comparing(ByteArrayLeakSuspect::totalBytes).reversed())
        .toList()) {
      findings.add(new Finding(
          artifact.artifact().id() + "-memory-byte-array-leak-" + index++,
          suspect.heapPercent() >= 25.0 ? FindingSeverity.HIGH : FindingSeverity.MEDIUM,
          FindingCategory.MEMORY,
          "Large byte[] retention",
          suspect.label() + " accounts for " + formatBytes(suspect.totalBytes())
              + " (" + formatPercent(suspect.heapPercent()) + " of heap) across "
              + suspect.arrayCount() + " byte arrays.",
          0.80,
          List.of(artifact.artifact().id()),
          List.of(),
          List.of(new Remediation(
              "Inspect large byte buffers and their owners.",
              RemediationType.CODE_CHANGE,
              "Bound buffer pools, release Netty/NIO buffers, or stream payloads instead of retaining full byte arrays.",
              "Large retained byte arrays are a common symptom of buffer, cache, or payload retention leaks.")),
          mapOf(
              "label", suspect.label(),
              "arrayCount", suspect.arrayCount(),
              "totalBytes", suspect.totalBytes(),
              "heapPercent", suspect.heapPercent(),
              "retentionPath", suspect.retentionPath())));
    }
  }

  private static String leakExplanation(HeapLeakSuspect suspect) {
    return "ClassName " + suspect.className() + ", loaded by ClassLoader "
        + suspect.classLoaderName() + ", retains " + formatBytes(suspect.retainedBytes())
        + " (" + formatPercent(suspect.heapPercent()) + ") of heap. The retention path is: "
        + retentionPath(suspect.retentionPath(), suspect.className()) + ".";
  }

  private static String retentionPath(List<String> path, String fallbackLeaf) {
    if (path == null || path.isEmpty()) {
      return "[GCRoot] \u2192 " + fallbackLeaf;
    }
    return String.join(" \u2192 ", path);
  }

  private static double option(AnalysisContext ctx, String key, double defaultValue) {
    Object value = ctx.options().get(key);
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      return Double.parseDouble(text);
    }
    return defaultValue;
  }

  private static Map<String, Object> mapOf(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      result.put((String) values[i], values[i + 1]);
    }
    return result;
  }

  private static String formatBytes(long bytes) {
    double mb = bytes / 1024.0 / 1024.0;
    if (mb >= 1024.0) {
      return String.format("%.1f GB", mb / 1024.0);
    }
    return String.format("%.1f MB", mb);
  }

  private static String formatPercent(double percent) {
    return String.format("%.1f%%", percent);
  }
}
