package io.helix.agent.core.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DiagnosticToolRegistry {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private final Map<String, DiagnosticTool<?>> tools;
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  public DiagnosticToolRegistry(List<DiagnosticTool<?>> tools) {
    Map<String, DiagnosticTool<?>> indexed = new LinkedHashMap<>();
    if (tools != null) {
      for (DiagnosticTool<?> tool : tools) {
        DiagnosticTool<?> previous = indexed.put(tool.definition().name(), tool);
        if (previous != null) {
          throw new IllegalArgumentException("Duplicate diagnostic tool: " + tool.definition().name());
        }
      }
    }
    this.tools = Map.copyOf(indexed);
  }

  public List<ToolDefinition> definitions() {
    return tools.values().stream()
        .map(DiagnosticTool::definition)
        .sorted(java.util.Comparator.comparing(ToolDefinition::name))
        .toList();
  }

  public ToolExecution execute(ToolCall call, InvestigationContext context) {
    DiagnosticTool<?> tool = tools.get(call.name());
    if (tool == null) {
      return new ToolExecution(call, ToolResult.invalid("Unknown diagnostic tool: " + call.name()));
    }
    try {
      return executeTyped(tool, call, context);
    } catch (IllegalArgumentException e) {
      return new ToolExecution(call, ToolResult.invalid(safeMessage(e)));
    } catch (RuntimeException e) {
      return new ToolExecution(call, ToolResult.error(safeMessage(e)));
    }
  }

  public Map<String, Object> argumentsAsMap(ToolCall call) {
    return objectMapper.convertValue(call.arguments(), MAP_TYPE);
  }

  public Map<String, Object> resultAsMap(ToolResult result) {
    return objectMapper.convertValue(result, MAP_TYPE);
  }

  private <I> ToolExecution executeTyped(
      DiagnosticTool<I> tool,
      ToolCall call,
      InvestigationContext context) {
    I input = objectMapper.convertValue(call.arguments(), tool.inputType());
    return new ToolExecution(call, tool.execute(input, context));
  }

  private static String safeMessage(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }
}
