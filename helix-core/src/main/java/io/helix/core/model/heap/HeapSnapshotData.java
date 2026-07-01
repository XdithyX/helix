package io.helix.core.model.heap;

import java.util.List;
import java.util.Map;

public record HeapSnapshotData(
    long totalHeapBytes,
    long totalObjects,
    long totalClasses,
    long gcRootCount,
    List<HeapClassHistogramEntry> classHistogram,
    List<HeapDominatorEntry> dominators,
    List<HeapLeakSuspect> leakSuspects,
    DuplicateStringSummary duplicateStrings,
    FinalizerQueueSummary finalizerQueue,
    List<ByteArrayLeakSuspect> byteArrayLeakSuspects,
    List<ClassLoaderLeakSuspect> classLoaderLeakSuspects,
    Map<String, Object> parserMetadata
) {
  public HeapSnapshotData {
    if (totalHeapBytes < 0) {
      throw new IllegalArgumentException("totalHeapBytes must be non-negative");
    }
    if (totalObjects < 0) {
      throw new IllegalArgumentException("totalObjects must be non-negative");
    }
    if (totalClasses < 0) {
      throw new IllegalArgumentException("totalClasses must be non-negative");
    }
    if (gcRootCount < 0) {
      throw new IllegalArgumentException("gcRootCount must be non-negative");
    }
    classHistogram = classHistogram == null ? List.of() : List.copyOf(classHistogram);
    dominators = dominators == null ? List.of() : List.copyOf(dominators);
    leakSuspects = leakSuspects == null ? List.of() : List.copyOf(leakSuspects);
    duplicateStrings = duplicateStrings == null ? DuplicateStringSummary.empty() : duplicateStrings;
    finalizerQueue = finalizerQueue == null ? FinalizerQueueSummary.empty() : finalizerQueue;
    byteArrayLeakSuspects =
        byteArrayLeakSuspects == null ? List.of() : List.copyOf(byteArrayLeakSuspects);
    classLoaderLeakSuspects =
        classLoaderLeakSuspects == null ? List.of() : List.copyOf(classLoaderLeakSuspects);
    parserMetadata = parserMetadata == null ? Map.of() : Map.copyOf(parserMetadata);
  }
}
