package io.helix.core.model;

import java.util.Map;

public record ToolTrace(
    String toolName,
    Map<String, Object> arguments,
    Map<String, Object> result
) {
  public ToolTrace {
    toolName = toolName == null ? "" : toolName;
    arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    result = result == null ? Map.of() : Map.copyOf(result);
  }
}
