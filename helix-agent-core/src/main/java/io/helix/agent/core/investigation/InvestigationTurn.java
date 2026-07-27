package io.helix.agent.core.investigation;

import java.util.List;

public record InvestigationTurn(
    String text,
    List<ToolCall> toolCalls
) {
  public InvestigationTurn {
    text = text == null ? "" : text;
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }
}
