package io.helix.agent.core.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

public record ToolCall(
    String id,
    String name,
    JsonNode arguments
) {
  public ToolCall {
    id = id == null ? "" : id;
    name = name == null ? "" : name;
    arguments = arguments == null ? JsonNodeFactory.instance.objectNode() : arguments;
  }
}
