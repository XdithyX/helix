package io.helix.parser.threaddump;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.ArtifactType;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.thread.JavaThreadState;
import io.helix.core.model.thread.ThreadDumpData;
import io.helix.core.model.thread.ThreadDumpFormat;
import io.helix.core.spi.ParseContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThreadDumpArtifactParserTest {
  @TempDir
  Path tempDir;

  @Test
  void recognizesThreadDumpFilesAndHints() {
    ThreadDumpArtifactParser parser = new ThreadDumpArtifactParser();

    assertThat(parser.canParse(Path.of("threads.txt"), null)).isTrue();
    assertThat(parser.canParse(Path.of("dump.tdump"), null)).isTrue();
    assertThat(parser.canParse(Path.of("server.log"), ArtifactType.THREAD_DUMP)).isTrue();
    assertThat(parser.canParse(Path.of("heap.hprof"), ArtifactType.HEAP_DUMP)).isFalse();
  }

  @Test
  void parsesHotSpotJstackFormat() throws Exception {
    ThreadDumpData data = parseFixture("deadlock-two-thread.txt");

    assertThat(data.format()).isEqualTo(ThreadDumpFormat.HOTSPOT);
    assertThat(data.snapshots()).hasSize(1);
    assertThat(data.snapshots().getFirst().threads())
        .extracting("name")
        .contains("Deadlock-1", "Deadlock-2", "Reference Handler");
    assertThat(data.snapshots().getFirst().threads())
        .filteredOn(thread -> thread.name().equals("Deadlock-1"))
        .singleElement()
        .satisfies(thread -> {
          assertThat(thread.state()).isEqualTo(JavaThreadState.BLOCKED);
          assertThat(thread.waitingToLock()).isNotNull();
          assertThat(thread.heldLocks()).hasSize(1);
          assertThat(thread.stackTrace()).hasSize(2);
        });
  }

  @Test
  void parsesOpenJ9Format() throws Exception {
    ThreadDumpData data = parseFixture("openj9-threaddump.txt");

    assertThat(data.format()).isEqualTo(ThreadDumpFormat.OPENJ9);
    assertThat(data.snapshots()).hasSize(1);
    assertThat(data.snapshots().getFirst().threads())
        .extracting("name")
        .contains("OpenJ9-Worker-1", "OpenJ9-Worker-2");
    assertThat(data.snapshots().getFirst().threads().getFirst().waitingToLock().ownerThreadName())
        .isEqualTo("OpenJ9-Worker-2");
  }

  @Test
  void parsesContinuousMultiSnapshotDumps() throws Exception {
    ThreadDumpData data = parseFixture("multi-snapshot-progression.txt");

    assertThat(data.snapshots()).hasSize(2);
    assertThat(data.snapshots().getFirst().capturedAt()).isBefore(data.snapshots().getLast().capturedAt());
    assertThat(data.snapshots().getLast().threads())
        .extracting("name")
        .contains("stuck-worker", "owner-worker");
  }

  @Test
  void toleratesMalformedThreadBlocksWithWarnings() throws Exception {
    Path malformed = tempDir.resolve("malformed.txt");
    Files.writeString(malformed, """
        Full thread dump OpenJDK 64-Bit Server VM:

        "truncated-thread" #1
        """);

    ParsedArtifact parsed = new ThreadDumpArtifactParser().parse(malformed, ParseContext.defaults());
    ThreadDumpData data = (ThreadDumpData) parsed.data().get(ThreadDumpArtifactParser.THREAD_DATA_KEY);

    assertThat(data.snapshots()).hasSize(1);
    assertThat(data.snapshots().getFirst().threads()).hasSize(1);
    assertThat(parsed.warnings()).isNotEmpty();
  }

  @Test
  void ignoresHotSpotDeadlockSummaryCopies() throws Exception {
    Path dump = tempDir.resolve("deadlock-with-summary.txt");
    Files.writeString(dump, """
        Full thread dump OpenJDK 64-Bit Server VM:

        "Deadlock-1" #11 waiting for monitor entry
           java.lang.Thread.State: BLOCKED (on object monitor)
                at com.example.Deadlock.first(Deadlock.java:21)
                - waiting to lock <0x1> (a java.lang.Object)
                - locked <0x2> (a java.lang.Object)

        "Deadlock-2" #12 waiting for monitor entry
           java.lang.Thread.State: BLOCKED (on object monitor)
                at com.example.Deadlock.second(Deadlock.java:31)
                - waiting to lock <0x2> (a java.lang.Object)
                - locked <0x1> (a java.lang.Object)

        Found one Java-level deadlock:
        =============================
        "Deadlock-1":
          waiting to lock monitor 0x1, which is held by "Deadlock-2"

        "Deadlock-2":
          waiting to lock monitor 0x2, which is held by "Deadlock-1"
        """);

    ParsedArtifact parsed = new ThreadDumpArtifactParser().parse(dump, ParseContext.defaults());
    ThreadDumpData data = (ThreadDumpData) parsed.data().get(ThreadDumpArtifactParser.THREAD_DATA_KEY);

    assertThat(data.snapshots().getFirst().threads())
        .extracting("name")
        .containsExactly("Deadlock-1", "Deadlock-2");
  }

  private static ThreadDumpData parseFixture(String fileName) throws Exception {
    Path path = threadFixture(fileName);
    ParsedArtifact parsed = new ThreadDumpArtifactParser().parse(path, ParseContext.defaults());
    return (ThreadDumpData) parsed.data().get(ThreadDumpArtifactParser.THREAD_DATA_KEY);
  }

  private static Path threadFixture(String fileName) {
    Path current = Path.of("").toAbsolutePath();
    for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
      Path fixture = candidate.resolve("helix-core/src/test/resources/test-data/threads").resolve(fileName);
      if (Files.isRegularFile(fixture)) {
        return fixture;
      }
    }
    throw new IllegalStateException("Could not find thread fixture " + fileName);
  }
}
