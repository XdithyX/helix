package io.helix.core.spi;

import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.TimelineEvent;
import java.util.List;

public interface Correlator {
  CorrelationResult correlate(List<ParsedArtifact> artifacts, List<TimelineEvent> timeline);
}
