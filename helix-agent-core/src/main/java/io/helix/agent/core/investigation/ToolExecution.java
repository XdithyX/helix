package io.helix.agent.core.investigation;

public record ToolExecution(
    ToolCall call,
    ToolResult result
) {}
