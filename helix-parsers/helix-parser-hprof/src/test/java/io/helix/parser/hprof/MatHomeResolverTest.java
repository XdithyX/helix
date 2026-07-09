package io.helix.parser.hprof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.helix.core.spi.ParseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MatHomeResolverTest {
  @TempDir
  Path tempDir;

  @Test
  void returnsEmptyWhenMatHomeIsUnset() throws Exception {
    assertThat(MatHomeResolver.fromEnvironment(Map.of())).isEmpty();
  }

  @Test
  void resolvesMatInstallPluginsDirectory() throws Exception {
    Path plugins = Files.createDirectories(tempDir.resolve("plugins"));
    Files.writeString(plugins.resolve("org.eclipse.mat.api_1.16.1.jar"), "");
    Files.writeString(plugins.resolve("org.eclipse.mat.parser_1.16.1.jar"), "");
    Files.writeString(plugins.resolve("org.eclipse.mat.hprof_1.16.1.jar"), "");
    Files.writeString(plugins.resolve("org.eclipse.core.runtime_1.0.0.jar"), "");

    MatHomeResolver.MatHome home = MatHomeResolver
        .fromEnvironment(Map.of(MatHomeResolver.ENV_NAME, tempDir.toString()))
        .orElseThrow();

    assertThat(home.home()).isEqualTo(tempDir.toAbsolutePath().normalize());
    assertThat(home.jarDirectory()).isEqualTo(plugins.toAbsolutePath().normalize());
    assertThat(home.jarUrls()).hasSize(4);
  }

  @Test
  void rejectsIncompleteMatInstall() throws Exception {
    Path plugins = Files.createDirectories(tempDir.resolve("plugins"));
    Files.writeString(plugins.resolve("org.eclipse.mat.api_1.16.1.jar"), "");

    assertThatThrownBy(() -> MatHomeResolver.fromEnvironment(Map.of(
        MatHomeResolver.ENV_NAME,
        tempDir.toString())))
        .isInstanceOf(ParseException.class)
        .hasMessageContaining("missing required MAT bundle");
  }
}
