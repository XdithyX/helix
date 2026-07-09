package io.helix.parser.gclog;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.ArtifactType;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.TimelineEventType;
import io.helix.core.model.gc.GcCollector;
import io.helix.core.model.gc.GcLogData;
import io.helix.core.model.gc.GcLogFormat;
import io.helix.core.spi.ParseContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GcLogArtifactParserTest {
  @Test
  void recognizesGcLogFilesAndHints() {
    GcLogArtifactParser parser = new GcLogArtifactParser();

    assertThat(parser.canParse(Path.of("gc.log"), null)).isTrue();
    assertThat(parser.canParse(Path.of("verbosegc.xml"), null)).isTrue();
    assertThat(parser.canParse(Path.of("server.out"), ArtifactType.GC_LOG)).isTrue();
    assertThat(parser.canParse(Path.of("heap.hprof"), ArtifactType.HEAP_DUMP)).isFalse();
  }

  @Test
  void parsesUnifiedG1GcLog() throws Exception {
    ParsedArtifact parsed = parse("g1gc-high-pause.log");
    GcLogData data = gcData(parsed);

    assertThat(data.format()).isEqualTo(GcLogFormat.UNIFIED);
    assertThat(data.events()).hasSize(5);
    assertThat(data.events()).extracting("collector").containsOnly(GcCollector.G1);
    assertThat(data.events()).anySatisfy(event -> {
      assertThat(event.systemGc()).isTrue();
      assertThat(event.fullGc()).isTrue();
      assertThat(event.durationMillis()).isEqualTo(2200.0);
    });
    assertThat(parsed.timelineEvents())
        .extracting("type")
        .contains(TimelineEventType.SYSTEM_GC, TimelineEventType.HUMONGOUS_ALLOCATION);
  }

  @Test
  void parsesUnifiedZgcLog() throws Exception {
    GcLogData data = gcData(parse("zgc-normal.log"));

    assertThat(data.format()).isEqualTo(GcLogFormat.UNIFIED);
    assertThat(data.events()).hasSize(2);
    assertThat(data.events()).extracting("collector").containsOnly(GcCollector.ZGC);
    assertThat(data.events().getFirst().heapAfterMb()).isEqualTo(512.0);
  }

  @Test
  void parsesOpenJ9VerboseGcXmlFragments() throws Exception {
    GcLogData data = gcData(parse("openj9-verbosegc.xml"));

    assertThat(data.format()).isEqualTo(GcLogFormat.OPENJ9_VERBOSE_GC);
    assertThat(data.events()).hasSize(2);
    assertThat(data.events().getLast().fullGc()).isTrue();
    assertThat(data.events().getLast().nurseryAfterMb()).isGreaterThan(0.0);
    assertThat(data.events().getLast().tenureAfterMb()).isGreaterThan(0.0);
  }

  @Test
  void parsesJdk8LegacyGcLog() throws Exception {
    GcLogData data = gcData(parse("jdk8-parallel.log"));

    assertThat(data.format()).isEqualTo(GcLogFormat.JDK8);
    assertThat(data.events()).hasSize(2);
    assertThat(data.events().getFirst().collector()).isEqualTo(GcCollector.PARALLEL);
    assertThat(data.events().getLast().systemGc()).isTrue();
  }

  @Test
  void toleratesTruncatedLogsWithWarnings() throws Exception {
    ParsedArtifact parsed = parse("truncated-gc.log");
    GcLogData data = gcData(parsed);

    assertThat(data.events()).hasSize(2);
    assertThat(parsed.warnings()).isNotEmpty();
    assertThat(data.events().getFirst().durationMillis()).isZero();
  }

  private static ParsedArtifact parse(String fileName) throws Exception {
    return new GcLogArtifactParser().parse(gcFixture(fileName), ParseContext.defaults());
  }

  private static GcLogData gcData(ParsedArtifact parsed) {
    return (GcLogData) parsed.data().get(GcLogArtifactParser.GC_DATA_KEY);
  }

  private static Path gcFixture(String fileName) {
    Path current = Path.of("").toAbsolutePath();
    for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
      Path fixture = candidate.resolve("helix-core/src/test/resources/test-data/gc").resolve(fileName);
      if (Files.isRegularFile(fixture)) {
        return fixture;
      }
    }
    throw new IllegalStateException("Could not find GC fixture " + fileName);
  }
}
