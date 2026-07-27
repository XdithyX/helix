package io.helix.agent.core.investigation;

import java.util.List;

public record InvestigationRequest(
    String systemInstruction,
    String initialPrompt,
    List<ToolDefinition> tools
) {
  public InvestigationRequest {
    systemInstruction = systemInstruction == null ? "" : systemInstruction;
    initialPrompt = initialPrompt == null ? "" : initialPrompt;
    tools = tools == null ? List.of() : List.copyOf(tools);
  }
}
