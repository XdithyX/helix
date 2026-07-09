package io.helix.parser.hprof;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.heap.HeapSnapshotData;
import io.helix.core.spi.ArtifactParser;
import io.helix.core.spi.ParseContext;
import io.helix.core.spi.ParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HprofArtifactParser implements ArtifactParser {
  static final String HEAP_DATA_KEY = "heapSnapshot";

  @Override
  public boolean canParse(Path path, ArtifactType hint) {
    if (hint != null && hint != ArtifactType.HEAP_DUMP) {
      return false;
    }
    return path != null
        && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".hprof");
  }

  @Override
  public ParsedArtifact parse(Path path, ParseContext ctx) throws ParseException {
    if (!canParse(path, ArtifactType.HEAP_DUMP)) {
      throw new ParseException("Not an HPROF heap dump: " + path);
    }
    if (!Files.isRegularFile(path)) {
      throw new ParseException("HPROF file does not exist or is not a regular file: " + path);
    }

    Path normalized = path.toAbsolutePath().normalize();
    try {
      ctx.progressMonitor().beginTask("Parse HPROF heap dump", Files.size(normalized));
      HeapSnapshotData heapData = new MatHeapSnapshotReader(ctx.progressMonitor()).read(normalized);
      Artifact artifact = new Artifact(
          artifactId(normalized),
          ArtifactType.HEAP_DUMP,
          Files.getLastModifiedTime(normalized).toInstant(),
          Map.of(
              "filename", normalized.getFileName().toString(),
              "size.bytes", Long.toString(Files.size(normalized)),
              "parser", "eclipse-mat"),
          jvmFlavour(heapData),
          normalized);
      ctx.progressMonitor().complete();
      return new ParsedArtifact(artifact, List.of(), Map.of(HEAP_DATA_KEY, heapData), List.of());
    } catch (IOException e) {
      throw new ParseException("Failed to read HPROF file metadata: " + normalized, e);
    } finally {
      ctx.progressMonitor().complete();
    }
  }

  @Override
  public ArtifactType artifactType() {
    return ArtifactType.HEAP_DUMP;
  }

  private static JvmFlavour jvmFlavour(HeapSnapshotData heapData) {
    Object flavour = heapData.parserMetadata().get("jvmFlavour");
    if (flavour instanceof String text) {
      try {
        return JvmFlavour.valueOf(text);
      } catch (IllegalArgumentException ignored) {
        return JvmFlavour.UNKNOWN;
      }
    }
    return JvmFlavour.UNKNOWN;
  }

  private static String artifactId(Path path) throws IOException {
    String fileName = path.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "-");
    long size = Files.size(path);
    Instant modified = Files.getLastModifiedTime(path).toInstant();
    return "heap-" + fileName + "-" + Long.toHexString((fileName + size + modified).hashCode());
  }
}
