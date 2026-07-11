package io.helix.reasoning.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.JvmProfile;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GeminiReasoningProviderTest {
  @Test
  void sendsOnlySanitizedDiagnosticFieldsToGeminiTransport() {
    AtomicReference<String> requestBody = new AtomicReference<>();
    GeminiReasoningProvider provider = new GeminiReasoningProvider(
        "test-key",
        "gemini-test",
        (apiKey, model, body) -> {
          requestBody.set(body);
          return """
              {"candidates":[{"content":{"parts":[{"text":"mocked narrative"}]}}]}
              """;
        });

    String explanation = provider.explain(finding(), report());

    assertThat(explanation).isEqualTo("mocked narrative");
    assertThat(requestBody.get()).contains("Large byte arrays");
    assertThat(requestBody.get()).contains("JDK 21");
    assertThat(requestBody.get()).doesNotContain("/secret/heap.hprof");
    assertThat(requestBody.get()).doesNotContain("heap dump bytes");
  }

  @Test
  void unavailableProviderDoesNotCallTransport() {
    AtomicReference<String> requestBody = new AtomicReference<>();
    GeminiReasoningProvider provider = new GeminiReasoningProvider(
        "",
        "gemini-test",
        (apiKey, model, body) -> {
          requestBody.set(body);
          throw new IOException("should not be called");
        });

    assertThat(provider.explain(finding(), report())).isEmpty();
    assertThat(requestBody).hasValue(null);
  }

  @Test
  void buildsGenerateContentUriForGeminiModelNames() {
    assertThat(GeminiReasoningProvider.generateContentUri(
            java.net.URI.create("https://generativelanguage.googleapis.com/v1beta/models/"),
            "gemini-2.5-flash",
            "key with spaces"))
        .hasToString("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=key+with+spaces");
  }

  private static DiagnosticReport report() {
    Artifact artifact = new Artifact(
        "artifact-1",
        ArtifactType.HEAP_DUMP,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of("note", "sanitized metadata"),
        JvmFlavour.HOTSPOT,
        Path.of("/secret/heap.hprof"));
    return new DiagnosticReport(
        "report",
        Instant.parse("2026-01-01T00:00:01Z"),
        List.of(artifact),
        List.of(finding()),
        List.of(),
        CorrelationSummary.empty(),
        new JvmProfile(JvmFlavour.HOTSPOT, "JDK 21", "Vendor", List.of("-Xmx1g"), Map.of(), Map.of()),
        null);
  }

  private static Finding finding() {
    return new Finding(
        "finding-1",
        FindingSeverity.HIGH,
        FindingCategory.MEMORY,
        "Large byte arrays",
        "byte arrays occupy a large shallow footprint",
        0.8,
        List.of("artifact-1"),
        List.of(),
        List.of(),
        Map.of("sanitized", "derived finding metadata"));
  }
}
