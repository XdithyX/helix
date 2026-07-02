package io.helix.core.spi;

import io.helix.core.model.DiagnosticReport;
import java.io.IOException;
import java.io.OutputStream;

public interface Reporter {
  void render(DiagnosticReport report, OutputStream out, ReportOptions options) throws IOException;

  String format();
}
