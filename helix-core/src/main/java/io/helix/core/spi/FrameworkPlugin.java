package io.helix.core.spi;

import io.helix.core.model.Finding;
import io.helix.core.model.FrameworkContext;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.StackFrame;
import io.helix.core.model.StackFrameAnnotation;
import java.util.List;
import java.util.Optional;

public interface FrameworkPlugin {
  String frameworkName();

  Optional<FrameworkContext> detect(ParsedArtifact artifact);

  List<StackFrameAnnotation> annotate(List<StackFrame> frames);

  List<Finding> frameworkSpecificFindings(ParsedArtifact artifact, FrameworkContext ctx);
}
