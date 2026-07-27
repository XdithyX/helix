package io.helix.agent.core.investigation;

import java.util.List;
import java.util.Map;

public record ToolResult(
    ToolStatus status,
    String summary,
    Map<String, Object> data,
    List<EvidenceReference> evidence,
    List<String> limitations,
    boolean truncated
) {
  public ToolResult {
    status = status == null ? ToolStatus.ERROR : status;
    summary = summary == null ? "" : summary;
    data = data == null ? Map.of() : Map.copyOf(data);
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
    limitations = limitations == null ? List.of() : List.copyOf(limitations);
  }

  public static ToolResult success(
      String summary,
      Map<String, Object> data,
      List<EvidenceReference> evidence,
      boolean truncated) {
    return new ToolResult(ToolStatus.SUCCESS, summary, data, evidence, List.of(), truncated);
  }

  public static ToolResult unavailable(String summary, String limitation) {
    return new ToolResult(
        ToolStatus.UNAVAILABLE,
        summary,
        Map.of(),
        List.of(),
        limitation == null || limitation.isBlank() ? List.of() : List.of(limitation),
        false);
  }

  public static ToolResult invalid(String summary) {
    return new ToolResult(ToolStatus.INVALID_ARGUMENTS, summary, Map.of(), List.of(), List.of(), false);
  }

  public static ToolResult error(String summary) {
    return new ToolResult(ToolStatus.ERROR, summary, Map.of(), List.of(), List.of(), false);
  }
}
