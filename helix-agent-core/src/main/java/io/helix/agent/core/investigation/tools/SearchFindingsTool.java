package io.helix.agent.core.investigation.tools;

import io.helix.agent.core.investigation.DiagnosticTool;
import io.helix.agent.core.investigation.EvidenceReference;
import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.agent.core.investigation.ToolDefinition;
import io.helix.agent.core.investigation.ToolResult;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingReliability;
import io.helix.core.model.FindingSeverity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SearchFindingsTool implements DiagnosticTool<SearchFindingsTool.Input> {
  public record Input(
      String category,
      String minimumSeverity,
      String reliability,
      String textContains,
      int limit
  ) {
    public Input {
      category = normalize(category);
      minimumSeverity = normalize(minimumSeverity);
      reliability = normalize(reliability);
      textContains = textContains == null ? "" : textContains.trim();
      limit = limit <= 0 ? 20 : Math.min(limit, 100);
    }
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        "search_findings",
        "Search deterministic Helix findings by category, severity, reliability, or text.",
        ToolSchemas.object(Map.of(
            "category", ToolSchemas.enumeration("Finding category or empty for all.", enumNames(FindingCategory.values())),
            "minimumSeverity", ToolSchemas.enumeration(
                "Minimum severity or empty for all.", enumNames(FindingSeverity.values())),
            "reliability", ToolSchemas.enumeration(
                "Reliability classification or empty for all.", enumNames(FindingReliability.values())),
            "textContains", ToolSchemas.string("Case-insensitive text in title or explanation."),
            "limit", ToolSchemas.integer("Maximum findings to return.", 1, 100))));
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public ToolResult execute(Input input, InvestigationContext context) {
    FindingCategory category = parseEnum(FindingCategory.class, input.category());
    FindingSeverity severity = parseEnum(FindingSeverity.class, input.minimumSeverity());
    FindingReliability reliability = parseEnum(FindingReliability.class, input.reliability());

    List<Finding> matches = context.report().findings().stream()
        .filter(finding -> category == null || finding.category() == category)
        .filter(finding -> severity == null || finding.severity().ordinal() <= severity.ordinal())
        .filter(finding -> reliability == null || finding.reliability() == reliability)
        .filter(finding -> contains(finding, input.textContains()))
        .sorted(Comparator.comparing(Finding::severity).thenComparing(Finding::title))
        .toList();
    List<Finding> returned = matches.stream().limit(input.limit()).toList();
    List<Map<String, Object>> rows = returned.stream().map(SearchFindingsTool::summary).toList();
    List<EvidenceReference> evidence = returned.stream()
        .flatMap(finding -> finding.artifactIds().stream()
            .map(id -> new EvidenceReference(id, null, "finding:" + finding.id())))
        .distinct()
        .toList();
    return ToolResult.success(
        "Matched " + matches.size() + " finding(s); returning " + returned.size() + ".",
        Map.of("totalMatches", matches.size(), "findings", rows),
        evidence,
        matches.size() > returned.size());
  }

  private static Map<String, Object> summary(Finding finding) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", finding.id());
    result.put("severity", finding.severity().name());
    result.put("category", finding.category().name());
    result.put("reliability", finding.reliability().name());
    result.put("confidence", finding.confidence());
    result.put("title", finding.title());
    result.put("explanation", finding.explanation());
    result.put("artifactIds", finding.artifactIds());
    return result;
  }

  private static boolean contains(Finding finding, String query) {
    if (query == null || query.isBlank()) {
      return true;
    }
    String lower = query.toLowerCase(Locale.ROOT);
    return finding.title().toLowerCase(Locale.ROOT).contains(lower)
        || finding.explanation().toLowerCase(Locale.ROOT).contains(lower);
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unsupported " + type.getSimpleName() + ": " + value);
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private static String[] enumNames(Enum<?>[] values) {
    List<String> names = new ArrayList<>();
    names.add("");
    for (Enum<?> value : values) {
      names.add(value.name());
    }
    return names.toArray(String[]::new);
  }
}
