package io.helix.agent.core.investigation;

import java.util.Map;

public record ToolDefinition(
    String name,
    String description,
    Map<String, Object> parametersJsonSchema
) {
  public ToolDefinition {
    name = name == null ? "" : name;
    description = description == null ? "" : description;
    parametersJsonSchema = parametersJsonSchema == null ? Map.of() : Map.copyOf(parametersJsonSchema);
  }
}
