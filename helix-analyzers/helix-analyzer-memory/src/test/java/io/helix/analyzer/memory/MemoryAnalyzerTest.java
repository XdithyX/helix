package io.helix.analyzer.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingReliability;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.heap.ByteArrayLeakSuspect;
import io.helix.core.model.heap.ClassLoaderLeakSuspect;
import io.helix.core.model.heap.DuplicateStringEntry;
import io.helix.core.model.heap.DuplicateStringSummary;
import io.helix.core.model.heap.FinalizerQueueSummary;
import io.helix.core.model.heap.HeapClassHistogramEntry;
import io.helix.core.model.heap.HeapDominatorEntry;
import io.helix.core.model.heap.HeapLeakSuspect;
import io.helix.core.model.heap.HeapSnapshotData;
import io.helix.core.spi.AnalysisContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryAnalyzerTest {
  @Test
  void producesPhaseTwoHeapFindings() {
    ParsedArtifact artifact = parsedArtifact(heapSnapshot());

    List<Finding> findings = new MemoryAnalyzer().analyze(artifact, AnalysisContext.defaults());

    assertThat(findings)
        .extracting(Finding::title)
        .contains(
            "Heap leak suspect: com.example.LeakyCache",
            "Top heap dominators",
            "Duplicate String instances",
            "Class histogram",
            "Possible classloader leak: org.example.WebAppClassLoader",
            "Finalizer queue backlog",
            "Large byte[] retention");
    assertThat(findings)
        .filteredOn(finding -> finding.title().equals("Heap leak suspect: com.example.LeakyCache"))
        .singleElement()
        .satisfies(finding -> {
          assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
          assertThat(finding.reliability()).isEqualTo(FindingReliability.HEURISTIC);
          assertThat(finding.explanation())
              .contains("ClassName com.example.LeakyCache")
              .contains("ClassLoader org.example.WebAppClassLoader")
              .contains("retains 300.0 MB")
              .contains("[GCRoot] \u2192 static cache \u2192 com.example.LeakyCache");
        });
  }

  private static ParsedArtifact parsedArtifact(HeapSnapshotData heapSnapshotData) {
    Artifact artifact = new Artifact(
        "heap-sample",
        ArtifactType.HEAP_DUMP,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of("filename", "sample.hprof"),
        JvmFlavour.HOTSPOT,
        Path.of("/tmp/sample.hprof"));
    return new ParsedArtifact(
        artifact,
        List.of(),
        Map.of(MemoryAnalyzer.HEAP_DATA_KEY, heapSnapshotData),
        List.of());
  }

  private static HeapSnapshotData heapSnapshot() {
    long heapBytes = 1024L * 1024L * 1024L;
    return new HeapSnapshotData(
        heapBytes,
        200_000,
        2_000,
        128,
        List.of(
            new HeapClassHistogramEntry("byte[]", "<bootstrap>", 10_000, 220L * 1024L * 1024L, 0),
            new HeapClassHistogramEntry("com.example.LeakyCache", "org.example.WebAppClassLoader", 1, 1024, 300L * 1024L * 1024L)),
        List.of(new HeapDominatorEntry(
            "com.example.LeakyCache",
            "com.example.LeakyCache",
            "org.example.WebAppClassLoader",
            1,
            300L * 1024L * 1024L,
            29.3,
            List.of("[GCRoot]", "static cache", "com.example.LeakyCache"))),
        List.of(new HeapLeakSuspect(
            "com.example.LeakyCache",
            "org.example.WebAppClassLoader",
            300L * 1024L * 1024L,
            29.3,
            List.of("[GCRoot]", "static cache", "com.example.LeakyCache"))),
        new DuplicateStringSummary(
            1,
            5_000,
            6L * 1024L * 1024L,
            List.of(new DuplicateStringEntry("customer-id", 5_000, 6L * 1024L * 1024L))),
        new FinalizerQueueSummary(2_500),
        List.of(new ByteArrayLeakSuspect(
            "byte[]",
            10_000,
            220L * 1024L * 1024L,
            21.5,
            List.of("[GCRoot]", "buffer cache", "byte[]"))),
        List.of(new ClassLoaderLeakSuspect(
            "org.example.WebAppClassLoader",
            300L * 1024L * 1024L,
            29.3,
            300,
            List.of("com.example.LeakyCache"))),
        Map.of("mat.version", "test"));
  }
}
