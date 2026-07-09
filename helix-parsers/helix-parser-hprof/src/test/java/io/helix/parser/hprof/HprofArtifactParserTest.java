package io.helix.parser.hprof;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.ArtifactType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HprofArtifactParserTest {
  @Test
  void recognizesHprofHeapDumps() {
    HprofArtifactParser parser = new HprofArtifactParser();

    assertThat(parser.canParse(Path.of("heap.hprof"), null)).isTrue();
    assertThat(parser.canParse(Path.of("heap.hprof"), ArtifactType.HEAP_DUMP)).isTrue();
    assertThat(parser.canParse(Path.of("threads.txt"), null)).isFalse();
    assertThat(parser.canParse(Path.of("heap.hprof"), ArtifactType.THREAD_DUMP)).isFalse();
  }
}
