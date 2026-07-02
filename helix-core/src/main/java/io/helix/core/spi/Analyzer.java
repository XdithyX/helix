package io.helix.core.spi;

import io.helix.core.model.ArtifactType;
import io.helix.core.model.Finding;
import io.helix.core.model.ParsedArtifact;
import java.util.List;
import java.util.Set;

public interface Analyzer {
  List<Finding> analyze(ParsedArtifact artifact, AnalysisContext ctx);

  Set<ArtifactType> supportedTypes();

  String analyzerId();
}
