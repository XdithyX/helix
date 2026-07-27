package io.helix.agent.core.investigation;

public interface DiagnosticTool<I> {
  ToolDefinition definition();

  Class<I> inputType();

  ToolResult execute(I input, InvestigationContext context);
}
