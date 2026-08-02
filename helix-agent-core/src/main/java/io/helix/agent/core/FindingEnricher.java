package io.helix.agent.core;

import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.TimelineEvent;
import io.helix.core.spi.ReasoningProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FindingEnricher {
  private FindingEnricher() {}

  public static List<Finding> enrich(
      String agentId,
      List<Finding> findings,
      FindingCategory category,
      String focus) {
    return findings.stream()
        .filter(finding -> finding.category() == category)
        .map(finding -> enrich(agentId, finding, focus))
        .toList();
  }

  public static List<Finding> enrich(
      String agentId,
      DiagnosticReport report,
      AgentContext context,
      FindingCategory category,
      String focus,
      String promptResource) {
    return report.findings().stream()
        .filter(finding -> finding.category() == category)
        .map(finding -> enrich(agentId, finding, focus, reasoning(finding, context, promptResource)))
        .toList();
  }

  public static Finding enrich(String agentId, Finding finding, String focus) {
    return enrich(agentId, finding, focus, "");
  }

  private static Finding enrich(String agentId, Finding finding, String focus, String reasoning) {
    return enrich(agentId, finding, focus, new ReasoningAttempt(reasoning, ""));
  }

  private static Finding enrich(String agentId, Finding finding, String focus, ReasoningAttempt reasoning) {
    Map<String, Object> rawData = new LinkedHashMap<>(finding.rawData());
    rawData.put("agent.id", agentId);
    rawData.put("agent.focus", focus);
    rawData.put("agent.enriched", true);
    if (!reasoning.text().isBlank()) {
      rawData.put("agent.reasoning", reasoning.text());
    }
    if (!reasoning.error().isBlank()) {
      rawData.put("agent.reasoning.error", reasoning.error());
    }
    return new Finding(
        finding.id(),
        finding.severity(),
        finding.category(),
        finding.title(),
        finding.explanation(),
        finding.confidence(),
        finding.artifactIds(),
        finding.evidence(),
        finding.remediations(),
        rawData,
        finding.reliability());
  }

  public static List<TimelineEvent> evidenceFor(List<Finding> findings) {
    return findings.stream()
        .flatMap(finding -> finding.evidence().stream())
        .distinct()
        .toList();
  }

  private static ReasoningAttempt reasoning(
      Finding finding,
      AgentContext context,
      String promptResource) {
    if (context == null || promptResource == null || promptResource.isBlank()) {
      return ReasoningAttempt.empty();
    }
    ReasoningProvider provider = context.reasoningProvider();
    if (provider == null || !provider.isAvailable()) {
      return ReasoningAttempt.empty();
    }
    try {
      String prompt = new PromptTemplateEngine().renderResource(promptResource, Map.of(
          "finding", AgentJson.toJson(finding),
          "evidence", AgentJson.toJson(finding.evidence()),
          "jvmProfile", AgentJson.toJson(context.jvmProfile()),
          "frameworkContext", AgentJson.toJson(context.frameworkContext())));
      return new ReasoningAttempt(provider.complete(prompt), "");
    } catch (RuntimeException e) {
      return new ReasoningAttempt("", safeError(e));
    }
  }

  private static String safeError(RuntimeException e) {
    Throwable cause = e.getCause() == null ? e : e.getCause();
    String message = cause.getMessage();
    if (message == null || message.isBlank()) {
      return cause.getClass().getSimpleName();
    }
    return cause.getClass().getSimpleName() + ": " + message;
  }

  private record ReasoningAttempt(String text, String error) {
    private ReasoningAttempt {
      text = text == null ? "" : text;
      error = error == null ? "" : error;
    }

    private static ReasoningAttempt empty() {
      return new ReasoningAttempt("", "");
    }
  }
}
