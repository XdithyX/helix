package io.helix.agent.core.investigation.tools;

import io.helix.agent.core.investigation.DiagnosticTool;
import io.helix.agent.core.investigation.EvidenceReference;
import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.agent.core.investigation.ToolDefinition;
import io.helix.agent.core.investigation.ToolResult;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.heap.ByteArrayLeakSuspect;
import io.helix.core.model.heap.ClassLoaderLeakSuspect;
import io.helix.core.model.heap.DuplicateStringEntry;
import io.helix.core.model.heap.HeapClassHistogramEntry;
import io.helix.core.model.heap.HeapDominatorEntry;
import io.helix.core.model.heap.HeapLeakSuspect;
import io.helix.core.model.heap.HeapSnapshotData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public final class QueryHeapTool implements DiagnosticTool<QueryHeapTool.Input> {
  public record Input(
      String operation,
      String classContains,
      int limit
  ) {
    public Input {
      operation = normalize(operation, "SUMMARY");
      classContains = clean(classContains);
      limit = limit <= 0 ? 20 : Math.min(limit, 100);
    }
  }

  private record HeapRef(String artifactId, java.time.Instant capturedAt, HeapSnapshotData heap) {}

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        "query_heap",
        "Query normalized heap snapshots for size summaries, histograms, dominators, leak suspects, byte arrays, classloaders, and duplicate strings.",
        ToolSchemas.object(Map.of(
            "operation", ToolSchemas.enumeration(
                "Result shape.",
                "SUMMARY", "TOP_CLASSES", "TOP_DOMINATORS", "LEAK_SUSPECTS",
                "BYTE_ARRAYS", "CLASSLOADERS", "DUPLICATE_STRINGS"),
            "classContains", ToolSchemas.string(
                "Case-insensitive class, label, or classloader filter where applicable."),
            "limit", ToolSchemas.integer("Maximum rows to return.", 1, 100))));
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public ToolResult execute(Input input, InvestigationContext context) {
    List<HeapRef> heaps = heaps(context);
    if (heaps.isEmpty()) {
      return ToolResult.unavailable(
          "No normalized heap-snapshot data is available.",
          "Supply a supported HPROF heap dump and configure Eclipse MAT.");
    }
    return switch (input.operation()) {
      case "SUMMARY" -> summaries(heaps);
      case "TOP_CLASSES" -> topClasses(heaps, input);
      case "TOP_DOMINATORS" -> topDominators(heaps, input);
      case "LEAK_SUSPECTS" -> leakSuspects(heaps, input);
      case "BYTE_ARRAYS" -> byteArrays(heaps, input);
      case "CLASSLOADERS" -> classLoaders(heaps, input);
      case "DUPLICATE_STRINGS" -> duplicateStrings(heaps, input);
      default -> ToolResult.invalid("Unsupported heap operation: " + input.operation());
    };
  }

  private static ToolResult summaries(List<HeapRef> heaps) {
    List<Map<String, Object>> rows = heaps.stream().map(ref -> {
      HeapSnapshotData heap = ref.heap();
      Map<String, Object> row = base(ref);
      row.put("totalHeapBytes", heap.totalHeapBytes());
      row.put("totalObjects", heap.totalObjects());
      row.put("totalClasses", heap.totalClasses());
      row.put("gcRootCount", heap.gcRootCount());
      row.put("dominatorCount", heap.dominators().size());
      row.put("leakSuspectCount", heap.leakSuspects().size());
      row.put("byteArraySuspectCount", heap.byteArrayLeakSuspects().size());
      row.put("classLoaderSuspectCount", heap.classLoaderLeakSuspects().size());
      row.put("pendingFinalizerObjects", heap.finalizerQueue().pendingObjects());
      row.put("duplicateStringInstances", heap.duplicateStrings().duplicateInstanceCount());
      row.put("duplicateStringWastedBytes", heap.duplicateStrings().wastedBytes());
      return row;
    }).toList();
    return ToolResult.success(
        "Returned summaries for " + heaps.size() + " heap snapshot(s).",
        Map.of("heaps", rows),
        heapEvidence(heaps, "heap:summary"),
        false);
  }

  private static ToolResult topClasses(List<HeapRef> heaps, Input input) {
    return rows(
        heaps,
        input,
        ref -> ref.heap().classHistogram().stream()
            .filter(entry -> contains(entry.className(), input.classContains())
                || contains(entry.classLoaderName(), input.classContains()))
            .sorted(Comparator.comparingLong(HeapClassHistogramEntry::shallowBytes).reversed())
            .map(entry -> map(
                "className", entry.className(),
                "classLoader", entry.classLoaderName(),
                "instanceCount", entry.instanceCount(),
                "shallowBytes", entry.shallowBytes(),
                "retainedBytes", entry.retainedBytes()))
            .toList(),
        "heap classes",
        "heap:class-histogram");
  }

  private static ToolResult topDominators(List<HeapRef> heaps, Input input) {
    return rows(
        heaps,
        input,
        ref -> ref.heap().dominators().stream()
            .filter(entry -> contains(entry.className(), input.classContains())
                || contains(entry.label(), input.classContains()))
            .sorted(Comparator.comparingLong(HeapDominatorEntry::retainedBytes).reversed())
            .map(entry -> map(
                "label", entry.label(),
                "className", entry.className(),
                "classLoader", entry.classLoaderName(),
                "objectCount", entry.objectCount(),
                "retainedBytes", entry.retainedBytes(),
                "heapPercent", entry.heapPercent(),
                "retentionPath", entry.retentionPath()))
            .toList(),
        "heap dominators",
        "heap:dominators");
  }

  private static ToolResult leakSuspects(List<HeapRef> heaps, Input input) {
    return rows(
        heaps,
        input,
        ref -> ref.heap().leakSuspects().stream()
            .filter(entry -> contains(entry.className(), input.classContains())
                || contains(entry.classLoaderName(), input.classContains()))
            .sorted(Comparator.comparingLong(HeapLeakSuspect::retainedBytes).reversed())
            .map(entry -> map(
                "className", entry.className(),
                "classLoader", entry.classLoaderName(),
                "retainedBytes", entry.retainedBytes(),
                "heapPercent", entry.heapPercent(),
                "retentionPath", entry.retentionPath()))
            .toList(),
        "heap leak suspects",
        "heap:leak-suspects");
  }

  private static ToolResult byteArrays(List<HeapRef> heaps, Input input) {
    return rows(
        heaps,
        input,
        ref -> ref.heap().byteArrayLeakSuspects().stream()
            .filter(entry -> contains(entry.label(), input.classContains()))
            .sorted(Comparator.comparingLong(ByteArrayLeakSuspect::totalBytes).reversed())
            .map(entry -> map(
                "label", entry.label(),
                "arrayCount", entry.arrayCount(),
                "totalBytes", entry.totalBytes(),
                "heapPercent", entry.heapPercent(),
                "retentionPath", entry.retentionPath()))
            .toList(),
        "byte-array suspects",
        "heap:byte-arrays");
  }

  private static ToolResult classLoaders(List<HeapRef> heaps, Input input) {
    return rows(
        heaps,
        input,
        ref -> ref.heap().classLoaderLeakSuspects().stream()
            .filter(entry -> contains(entry.classLoaderName(), input.classContains())
                || entry.retainedClasses().stream()
                    .anyMatch(value -> contains(value, input.classContains())))
            .sorted(Comparator.comparingLong(ClassLoaderLeakSuspect::retainedBytes).reversed())
            .map(entry -> map(
                "classLoader", entry.classLoaderName(),
                "retainedBytes", entry.retainedBytes(),
                "heapPercent", entry.heapPercent(),
                "definedClassCount", entry.definedClassCount(),
                "retainedClasses", entry.retainedClasses()))
            .toList(),
        "classloader suspects",
        "heap:classloaders");
  }

  private static ToolResult duplicateStrings(List<HeapRef> heaps, Input input) {
    return rows(
        heaps,
        input,
        ref -> ref.heap().duplicateStrings().topDuplicates().stream()
            .filter(entry -> contains(entry.valuePreview(), input.classContains()))
            .sorted(Comparator.comparingLong(DuplicateStringEntry::wastedBytes).reversed())
            .map(entry -> map(
                "valuePreview", entry.valuePreview(),
                "instances", entry.instances(),
                "wastedBytes", entry.wastedBytes()))
            .toList(),
        "duplicate-string groups",
        "heap:duplicate-strings");
  }

  private static ToolResult rows(
      List<HeapRef> heaps,
      Input input,
      Function<HeapRef, List<Map<String, Object>>> extractor,
      String label,
      String evidenceLabel) {
    List<Map<String, Object>> all = new ArrayList<>();
    List<EvidenceReference> evidence = new ArrayList<>();
    for (HeapRef ref : heaps) {
      for (Map<String, Object> value : extractor.apply(ref)) {
        Map<String, Object> row = new LinkedHashMap<>(base(ref));
        row.putAll(value);
        all.add(row);
        evidence.add(new EvidenceReference(ref.artifactId(), ref.capturedAt(), evidenceLabel));
      }
    }
    List<Map<String, Object>> returned = all.stream().limit(input.limit()).toList();
    return ToolResult.success(
        "Found " + all.size() + " " + label + "; returning " + returned.size() + ".",
        Map.of("totalMatches", all.size(), "rows", returned),
        evidence.stream().limit(input.limit()).toList(),
        all.size() > returned.size());
  }

  private static List<HeapRef> heaps(InvestigationContext context) {
    List<HeapRef> heaps = new ArrayList<>();
    for (ParsedArtifact parsed : context.parsedArtifacts()) {
      parsed.data().values().stream()
          .filter(HeapSnapshotData.class::isInstance)
          .map(HeapSnapshotData.class::cast)
          .map(heap -> new HeapRef(
              parsed.artifact().id(),
              parsed.artifact().capturedAt(),
              heap))
          .forEach(heaps::add);
    }
    return heaps;
  }

  private static Map<String, Object> base(HeapRef ref) {
    return map(
        "artifactId", ref.artifactId(),
        "capturedAt", ref.capturedAt().toString());
  }

  private static List<EvidenceReference> heapEvidence(
      List<HeapRef> heaps,
      String reference) {
    return heaps.stream()
        .map(ref -> new EvidenceReference(ref.artifactId(), ref.capturedAt(), reference))
        .toList();
  }

  private static Map<String, Object> map(Object... entries) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < entries.length; index += 2) {
      result.put((String) entries[index], entries[index + 1]);
    }
    return result;
  }

  private static boolean contains(String value, String query) {
    return query.isBlank() || value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
  }

  private static String normalize(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
