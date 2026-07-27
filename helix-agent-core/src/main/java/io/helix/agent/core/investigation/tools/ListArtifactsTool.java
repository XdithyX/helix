package io.helix.agent.core.investigation.tools;

import io.helix.agent.core.investigation.DiagnosticTool;
import io.helix.agent.core.investigation.EvidenceReference;
import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.agent.core.investigation.ToolDefinition;
import io.helix.agent.core.investigation.ToolResult;
import io.helix.core.model.ParsedArtifact;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ListArtifactsTool implements DiagnosticTool<ListArtifactsTool.Input> {
  public record Input() {}

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        "list_artifacts",
        "List supplied JVM artifacts, capture times, normalized data types, warnings, and timeline coverage.",
        ToolSchemas.object(Map.of()));
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public ToolResult execute(Input input, InvestigationContext context) {
    List<Map<String, Object>> artifacts = context.parsedArtifacts().stream()
        .map(ListArtifactsTool::artifactSummary)
        .toList();
    List<EvidenceReference> evidence = context.parsedArtifacts().stream()
        .map(parsed -> new EvidenceReference(
            parsed.artifact().id(),
            parsed.artifact().capturedAt(),
            "artifact:" + parsed.artifact().id()))
        .toList();
    return ToolResult.success(
        "The investigation contains " + artifacts.size() + " parsed artifact(s).",
        Map.of("artifacts", artifacts),
        evidence,
        false);
  }

  private static Map<String, Object> artifactSummary(ParsedArtifact parsed) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("artifactId", parsed.artifact().id());
    result.put("type", parsed.artifact().type().name());
    result.put("capturedAt", parsed.artifact().capturedAt().toString());
    result.put("timelineEvents", parsed.timelineEvents().size());
    result.put("dataTypes", parsed.data().values().stream()
        .map(value -> value.getClass().getSimpleName())
        .sorted()
        .toList());
    result.put("warnings", parsed.warnings());
    Instant first = parsed.timelineEvents().stream()
        .map(event -> event.timestamp())
        .min(Instant::compareTo)
        .orElse(null);
    Instant last = parsed.timelineEvents().stream()
        .map(event -> event.timestamp())
        .max(Instant::compareTo)
        .orElse(null);
    if (first != null) {
      result.put("timelineFrom", first.toString());
      result.put("timelineUntil", last.toString());
    }
    return result;
  }
}
