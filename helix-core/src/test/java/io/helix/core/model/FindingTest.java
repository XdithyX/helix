package io.helix.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FindingTest {
  @Test
  void defaultsExistingFindingsToDerivedReliability() {
    Finding finding = finding();

    assertThat(finding.reliability()).isEqualTo(FindingReliability.DERIVED);
  }

  @Test
  void canClassifyFindingWithoutChangingItsEvidence() {
    Finding original = finding();

    Finding classified = original.withReliability(FindingReliability.HEURISTIC);

    assertThat(classified.reliability()).isEqualTo(FindingReliability.HEURISTIC);
    assertThat(classified.evidence()).isEqualTo(original.evidence());
    assertThat(classified.rawData()).isEqualTo(original.rawData());
  }

  private static Finding finding() {
    return new Finding(
        "finding-1",
        FindingSeverity.MEDIUM,
        FindingCategory.THREADING,
        "Contention suspect",
        "Several threads share the same blocked stack.",
        0.75,
        List.of("artifact-1"),
        List.of(),
        List.of(),
        Map.of("blockedThreads", 4));
  }
}
