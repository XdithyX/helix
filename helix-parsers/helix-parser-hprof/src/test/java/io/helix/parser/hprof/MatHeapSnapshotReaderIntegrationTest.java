package io.helix.parser.hprof;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.helix.core.model.heap.HeapSnapshotData;
import io.helix.core.progress.ProgressMonitor;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class MatHeapSnapshotReaderIntegrationTest {
  private static final String DUPLICATE_VALUE = "helix-integration-duplicate-value";
  private static final List<Object> RETAINED = new ArrayList<>();

  @TempDir
  Path tempDir;

  @Test
  @EnabledIfEnvironmentVariable(named = MatHomeResolver.ENV_NAME, matches = ".+")
  void readsGeneratedHprofWithExternalMatRuntime() throws Exception {
    Path heapDump = tempDir.resolve("generated-heap.hprof");
    retainHeapFixtures();
    try {
      dumpHeap(heapDump);

      HeapSnapshotData snapshot = new MatHeapSnapshotReader(ProgressMonitor.noop()).read(heapDump);

      assertThat(snapshot.totalHeapBytes()).isPositive();
      assertThat(snapshot.totalObjects()).isPositive();
      assertThat(snapshot.classHistogram()).isNotEmpty();
      assertThat(snapshot.dominators()).isNotEmpty();
      assertThat(snapshot.duplicateStrings().topDuplicates())
          .anySatisfy(entry -> {
            assertThat(entry.valuePreview()).isEqualTo(DUPLICATE_VALUE);
            assertThat(entry.instances()).isGreaterThanOrEqualTo(1_000);
            assertThat(entry.wastedBytes()).isPositive();
          });
      assertThat(snapshot.parserMetadata())
          .containsEntry("mat.integration", "query-report");
    } finally {
      RETAINED.clear();
      Files.deleteIfExists(heapDump);
    }
  }

  private static void retainHeapFixtures() {
    RETAINED.clear();
    for (int i = 0; i < 1_500; i++) {
      RETAINED.add(new String(DUPLICATE_VALUE));
    }
    RETAINED.add(new byte[2 * 1024 * 1024]);
  }

  private static void dumpHeap(Path heapDump) throws Exception {
    HotSpotDiagnosticMXBean diagnostic =
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    diagnostic.dumpHeap(heapDump.toAbsolutePath().toString(), true);
  }
}
