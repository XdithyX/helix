package io.helix.core.spi;

import java.util.Map;

public record ReportOptions(
    String format,
    boolean includeRawData,
    Map<String, Object> attributes
) {
  public ReportOptions {
    if (format == null || format.isBlank()) {
      throw new IllegalArgumentException("format must not be blank");
    }
    format = format.toLowerCase();
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  public static ReportOptions forFormat(String format) {
    return new ReportOptions(format, false, Map.of());
  }
}
