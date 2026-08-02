package io.helix.agent.core.investigation;

import io.helix.agent.core.AgentJson;
import io.helix.core.model.InvestigationResult;
import io.helix.core.model.InvestigationStatus;
import io.helix.core.model.ToolTrace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InvestigationOrchestrator {
  public static final String CONCLUDE_TOOL = "conclude_investigation";
  public static final String INSUFFICIENT_TOOL = "report_insufficient_evidence";
  private static final int DEFAULT_MAX_STEPS = 6;

  private final DiagnosticToolRegistry registry;
  private final int maxSteps;

  public InvestigationOrchestrator(DiagnosticToolRegistry registry) {
    this(registry, DEFAULT_MAX_STEPS);
  }

  public InvestigationOrchestrator(DiagnosticToolRegistry registry, int maxSteps) {
    this.registry = registry;
    if (maxSteps < 1) {
      throw new IllegalArgumentException("maxSteps must be at least 1");
    }
    this.maxSteps = maxSteps;
  }

  public InvestigationResult investigate(
      InvestigationContext context,
      InvestigationModel model) {
    if (model == null || !model.isAvailable()) {
      return new InvestigationResult(
          context.question(),
          InvestigationStatus.MODEL_UNAVAILABLE,
          "No tool-capable investigation model is configured.",
          List.of());
    }

    List<ToolTrace> traces = new ArrayList<>();
    List<ToolExecution> previousExecutions = List.of();
    try {
      InvestigationSession session = model.openSession(request(context));
      for (int step = 0; step < maxSteps; step++) {
        InvestigationTurn turn = session.next(previousExecutions);
        if (turn.toolCalls().isEmpty()) {
          InvestigationStatus status = turn.text().isBlank()
              ? InvestigationStatus.INSUFFICIENT_EVIDENCE
              : InvestigationStatus.CONCLUDED;
          String answer = turn.text().isBlank()
              ? "The model returned no conclusion and requested no diagnostic tool."
              : turn.text();
          return new InvestigationResult(context.question(), status, answer, traces);
        }

        List<ToolCall> diagnosticCalls = turn.toolCalls().stream()
            .filter(call -> !isControlCall(call))
            .toList();
        if (diagnosticCalls.isEmpty()) {
          ToolCall control = turn.toolCalls().getFirst();
          if (CONCLUDE_TOOL.equals(control.name())) {
            return new InvestigationResult(
                context.question(),
                InvestigationStatus.CONCLUDED,
                requiredText(control, "answer"),
                traces);
          }
          return new InvestigationResult(
              context.question(),
              InvestigationStatus.INSUFFICIENT_EVIDENCE,
              insufficientAnswer(control),
              traces);
        }

        List<ToolExecution> executions = new ArrayList<>();
        for (ToolCall call : diagnosticCalls) {
          ToolExecution execution = registry.execute(call, context);
          executions.add(execution);
          traces.add(new ToolTrace(
              call.name(),
              registry.argumentsAsMap(call),
              registry.resultAsMap(execution.result())));
        }
        previousExecutions = List.copyOf(executions);
      }
      return new InvestigationResult(
          context.question(),
          InvestigationStatus.STEP_LIMIT_REACHED,
          "Investigation stopped after reaching the " + maxSteps + "-step tool budget.",
          traces);
    } catch (RuntimeException e) {
      return new InvestigationResult(
          context.question(),
          InvestigationStatus.MODEL_ERROR,
          "Investigation model failed: " + safeMessage(e),
          traces);
    }
  }

  private InvestigationRequest request(InvestigationContext context) {
    List<ToolDefinition> definitions = new ArrayList<>(registry.definitions());
    definitions.add(controlDefinition(
        CONCLUDE_TOOL,
        "Finish with an evidence-backed answer to the user's question.",
        Map.of(
            "answer", Map.of("type", "string", "description", "Concise conclusion with supporting evidence and limitations.")),
        List.of("answer")));
    definitions.add(controlDefinition(
        INSUFFICIENT_TOOL,
        "Finish when the supplied artifacts cannot support a reliable answer.",
        Map.of(
            "reason", Map.of("type", "string", "description", "Why the evidence is insufficient."),
            "requiredEvidence", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Additional artifacts or telemetry needed.")),
        List.of("reason")));

    String initialPrompt = """
        User question:
        %s

        Available artifact summary:
        %s

        Deterministic finding index:
        %s

        Timeline event count: %d
        """.formatted(
        context.question(),
        AgentJson.toJson(context.report().artifacts().stream()
            .map(artifact -> Map.of(
                "artifactId", artifact.id(),
                "type", artifact.type().name(),
                "capturedAt", artifact.capturedAt().toString(),
                "jvmFlavour", artifact.jvmFlavour().name()))
            .toList()),
        AgentJson.toJson(findingIndex(context)),
        context.report().timeline().size());
    return new InvestigationRequest(systemInstruction(), initialPrompt, definitions);
  }

  private static String systemInstruction() {
    return """
        You are the Helix JVM investigation agent. Answer only from tool results and the supplied
        deterministic report. Use tools for targeted evidence instead of assuming details.
        The initial finding index contains counts only; use search_findings to retrieve relevant
        finding details.
        FACT findings may be treated as proven only within the artifact that supports them.
        DERIVED findings are calculations; HEURISTIC findings require corroboration.
        For direct questions, use the minimum tools needed. For causal questions, test plausible
        alternatives and timing. Never claim that one event caused another from temporal proximity
        alone. Use conclude_investigation when the evidence supports an answer. Use
        report_insufficient_evidence when the supplied artifacts cannot answer reliably.
        Do not request arbitrary files, shell commands, production changes, or unregistered tools.
        """;
  }

  private static ToolDefinition controlDefinition(
      String name,
      String description,
      Map<String, Object> properties,
      List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", required);
    schema.put("additionalProperties", false);
    return new ToolDefinition(name, description, schema);
  }

  private static String requiredText(ToolCall call, String field) {
    String value = call.arguments().path(field).asText("").trim();
    return value.isEmpty() ? "The investigation completed without a written conclusion." : value;
  }

  private static String insufficientAnswer(ToolCall call) {
    String reason = requiredText(call, "reason");
    List<String> requiredEvidence = new ArrayList<>();
    call.arguments().path("requiredEvidence").forEach(node -> {
      String value = node.asText("").trim();
      if (!value.isEmpty()) {
        requiredEvidence.add(value);
      }
    });
    if (requiredEvidence.isEmpty()) {
      return reason;
    }
    return reason + " Required evidence: " + String.join(", ", requiredEvidence) + ".";
  }

  private static String safeMessage(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  private static boolean isControlCall(ToolCall call) {
    return CONCLUDE_TOOL.equals(call.name()) || INSUFFICIENT_TOOL.equals(call.name());
  }

  private static Map<String, Object> findingIndex(InvestigationContext context) {
    Map<String, Long> bySeverity = new LinkedHashMap<>();
    Map<String, Long> byCategory = new LinkedHashMap<>();
    Map<String, Long> byReliability = new LinkedHashMap<>();
    context.report().findings().forEach(finding -> {
      bySeverity.merge(finding.severity().name(), 1L, Long::sum);
      byCategory.merge(finding.category().name(), 1L, Long::sum);
      byReliability.merge(finding.reliability().name(), 1L, Long::sum);
    });
    return Map.of(
        "total", context.report().findings().size(),
        "bySeverity", bySeverity,
        "byCategory", byCategory,
        "byReliability", byReliability);
  }
}
