package io.helix.core.spi;

import io.helix.core.model.ArtifactType;
import io.helix.core.model.ParsedArtifact;
import java.nio.file.Path;

public interface ArtifactParser {
  boolean canParse(Path path, ArtifactType hint);

  ParsedArtifact parse(Path path, ParseContext ctx) throws ParseException;

  ArtifactType artifactType();
}
