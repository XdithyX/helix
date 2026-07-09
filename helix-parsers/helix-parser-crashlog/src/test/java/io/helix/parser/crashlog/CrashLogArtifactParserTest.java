package io.helix.parser.crashlog;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.ArtifactType;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.TimelineEventType;
import io.helix.core.model.crash.CrashLogData;
import io.helix.core.spi.ParseContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CrashLogArtifactParserTest {
  @Test
  void recognizesCrashLogFilesAndHints() {
    CrashLogArtifactParser parser = new CrashLogArtifactParser();

    assertThat(parser.canParse(Path.of("hs_err_pid123.log"), null)).isTrue();
    assertThat(parser.canParse(Path.of("crash.txt"), ArtifactType.CRASH_LOG)).isTrue();
    assertThat(parser.canParse(Path.of("gc.log"), ArtifactType.GC_LOG)).isFalse();
  }

  @Test
  void parsesSigsegvHsErrLog() throws Exception {
    ParsedArtifact parsed = parse("hs_err_pid_segv.log");
    CrashLogData data = crashData(parsed);

    assertThat(data.signal()).isEqualTo("SIGSEGV");
    assertThat(data.problematicFrame().library()).isEqualTo("libnative_app.dylib");
    assertThat(data.stackFrames()).hasSize(2);
    assertThat(data.registers()).containsEntry("RBX", "0x00000000deadbeef");
    assertThat(data.dynamicLibraries()).anyMatch(line -> line.contains("libnative_app"));
    assertThat(parsed.timelineEvents()).extracting("type").contains(TimelineEventType.NATIVE_CRASH);
  }

  @Test
  void parsesOomCrashLog() throws Exception {
    ParsedArtifact parsed = parse("hs_err_pid_oom.log");
    CrashLogData data = crashData(parsed);

    assertThat(data.oomInHeader()).isTrue();
    assertThat(parsed.timelineEvents()).extracting("type").contains(TimelineEventType.OOM);
  }

  private static ParsedArtifact parse(String fileName) throws Exception {
    return new CrashLogArtifactParser().parse(crashFixture(fileName), ParseContext.defaults());
  }

  private static CrashLogData crashData(ParsedArtifact parsed) {
    return (CrashLogData) parsed.data().get(CrashLogArtifactParser.CRASH_DATA_KEY);
  }

  private static Path crashFixture(String fileName) {
    Path current = Path.of("").toAbsolutePath();
    for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
      Path fixture = candidate.resolve("helix-core/src/test/resources/test-data/crash").resolve(fileName);
      if (Files.isRegularFile(fixture)) {
        return fixture;
      }
    }
    throw new IllegalStateException("Could not find crash fixture " + fileName);
  }
}
