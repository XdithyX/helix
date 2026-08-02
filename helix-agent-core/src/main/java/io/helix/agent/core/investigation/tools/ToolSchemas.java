package io.helix.agent.core.investigation.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ToolSchemas {
  private ToolSchemas() {}

  static Map<String, Object> object(Map<String, Object> properties) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("additionalProperties", false);
    return schema;
  }

  static Map<String, Object> string(String description) {
    return Map.of("type", "string", "description", description);
  }

  static Map<String, Object> integer(String description, int minimum, int maximum) {
    return Map.of(
        "type", "integer",
        "description", description,
        "minimum", minimum,
        "maximum", maximum);
  }

  static Map<String, Object> number(String description, double minimum) {
    return Map.of(
        "type", "number",
        "description", description,
        "minimum", minimum);
  }

  static Map<String, Object> enumeration(String description, String... values) {
    return Map.of(
        "type", "string",
        "description", description,
        "enum", List.of(values));
  }
}
