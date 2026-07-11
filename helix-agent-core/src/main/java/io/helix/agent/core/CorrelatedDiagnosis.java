package io.helix.agent.core;

import java.util.List;

public record CorrelatedDiagnosis(
    String narrative,
    List<CorrelationLink> links,
    double confidence
) {
  public CorrelatedDiagnosis {
    narrative = narrative == null ? "" : narrative;
    links = links == null ? List.of() : List.copyOf(links);
    if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
    }
  }

  public static CorrelatedDiagnosis empty() {
    return new CorrelatedDiagnosis("", List.of(), 0.0);
  }
}
