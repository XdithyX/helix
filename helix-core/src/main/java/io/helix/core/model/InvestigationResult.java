package io.helix.core.model;

import java.util.List;

public record InvestigationResult(
    String question,
    InvestigationStatus status,
    String answer,
    List<ToolTrace> toolTraces
) {
  public InvestigationResult {
    question = question == null ? "" : question;
    status = status == null ? InvestigationStatus.INSUFFICIENT_EVIDENCE : status;
    answer = answer == null ? "" : answer;
    toolTraces = toolTraces == null ? List.of() : List.copyOf(toolTraces);
  }
}
