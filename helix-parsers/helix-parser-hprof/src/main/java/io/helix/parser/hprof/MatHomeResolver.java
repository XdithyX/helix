package io.helix.parser.hprof;

import io.helix.core.spi.ParseException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

final class MatHomeResolver {
  static final String ENV_NAME = "HELIX_MAT_HOME";
  private static final Set<String> REQUIRED_BUNDLES = Set.of(
      "org.eclipse.mat.api",
      "org.eclipse.mat.parser",
      "org.eclipse.mat.hprof");

  private MatHomeResolver() {}

  static Optional<MatHome> fromEnvironment() throws ParseException {
    return fromEnvironment(System.getenv());
  }

  static Optional<MatHome> fromEnvironment(Map<String, String> environment) throws ParseException {
    String configured = environment.get(ENV_NAME);
    if (configured == null || configured.isBlank()) {
      return Optional.empty();
    }

    Path configuredPath = Path.of(configured).toAbsolutePath().normalize();
    if (!Files.isDirectory(configuredPath)) {
      throw new ParseException(ENV_NAME + " must point to a MAT directory, but was: " + configuredPath);
    }

    Path jarDirectory = jarDirectory(configuredPath);
    List<Path> jars = jars(jarDirectory);
    if (jars.isEmpty()) {
      throw new ParseException(ENV_NAME + " does not contain MAT jars. Expected jars in "
          + configuredPath + " or " + configuredPath.resolve("plugins"));
    }

    List<String> missing = REQUIRED_BUNDLES.stream()
        .filter(required -> jars.stream().noneMatch(jar -> startsWithBundleName(jar, required)))
        .sorted()
        .toList();
    if (!missing.isEmpty()) {
      throw new ParseException(ENV_NAME + " is missing required MAT bundle(s): "
          + String.join(", ", missing) + ". Point it at a MAT installation or update-site plugins directory.");
    }

    return Optional.of(new MatHome(configuredPath, jarDirectory, urls(jars)));
  }

  private static Path jarDirectory(Path configuredPath) {
    Path plugins = configuredPath.resolve("plugins");
    if (Files.isDirectory(plugins)) {
      return plugins;
    }
    return configuredPath;
  }

  private static List<Path> jars(Path jarDirectory) throws ParseException {
    try (Stream<Path> stream = Files.list(jarDirectory)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    } catch (IOException e) {
      throw new ParseException("Failed to read MAT jar directory: " + jarDirectory, e);
    }
  }

  private static boolean startsWithBundleName(Path jar, String bundleName) {
    String fileName = jar.getFileName().toString();
    return fileName.equals(bundleName + ".jar") || fileName.startsWith(bundleName + "_");
  }

  private static List<URL> urls(List<Path> jars) throws ParseException {
    List<URL> urls = new ArrayList<>();
    for (Path jar : jars) {
      try {
        urls.add(jar.toUri().toURL());
      } catch (MalformedURLException e) {
        throw new ParseException("Invalid MAT jar URL: " + jar, e);
      }
    }
    return List.copyOf(urls);
  }

  record MatHome(Path home, Path jarDirectory, List<URL> jarUrls) {
    MatHome {
      home = home.toAbsolutePath().normalize();
      jarDirectory = jarDirectory.toAbsolutePath().normalize();
      jarUrls = List.copyOf(jarUrls);
    }
  }
}
