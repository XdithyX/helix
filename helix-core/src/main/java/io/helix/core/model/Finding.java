package io.helix.core.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Finding(
    String id,
    FindingSeverity severity,
    FindingCategory category,
    String title,
    String explanation,
    double confidence,
    List<String> artifactIds,
    List<TimelineEvent> evidence,
    List<Remediation> remediations,
    Map<String, Object> rawData,
    FindingReliability reliability
) {
  public Finding {
    id = requireText(id, "id");
    severity = Objects.requireNonNull(severity, "severity");
    category = Objects.requireNonNull(category, "category");
    title = requireText(title, "title");
    explanation = Objects.requireNonNullElse(explanation, "");
    if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
    }
    artifactIds = artifactIds == null ? List.of() : List.copyOf(artifactIds);
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
    remediations = remediations == null ? List.of() : List.copyOf(remediations);
    rawData = rawData == null ? Map.of() : Map.copyOf(rawData);
    reliability = reliability == null ? FindingReliability.DERIVED : reliability;
  }

  public Finding(
      String id,
      FindingSeverity severity,
      FindingCategory category,
      String title,
      String explanation,
      double confidence,
      List<String> artifactIds,
      List<TimelineEvent> evidence,
      List<Remediation> remediations,
      Map<String, Object> rawData) {
    this(
        id,
        severity,
        category,
        title,
        explanation,
        confidence,
        artifactIds,
        evidence,
        remediations,
        rawData,
        FindingReliability.DERIVED);
  }

  public Finding withReliability(FindingReliability value) {
    return new Finding(
        id,
        severity,
        category,
        title,
        explanation,
        confidence,
        artifactIds,
        evidence,
        remediations,
        rawData,
        value);
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
