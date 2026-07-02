package io.helix.core.spi;

import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;

public interface ReasoningProvider {
  default String complete(String prompt) {
    return "";
  }

  String explain(Finding finding, DiagnosticReport context);

  String narrateSummary(DiagnosticReport report);

  boolean isAvailable();
}
