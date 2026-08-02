package io.helix.agent.core.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.helix.agent.core.investigation.tools.SearchFindingsTool;
import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingReliability;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.InvestigationStatus;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.JvmProfile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InvestigationOrchestratorTest {
  @Test
  void executesDeterministicToolThenReturnsExplicitConclusion() {
    AtomicInteger turns = new AtomicInteger();
    InvestigationModel model = model(previous -> {
      if (turns.getAndIncrement() == 0) {
        return new InvestigationTurn("", List.of(
            new ToolCall(
                "call-1",
                "search_findings",
                JsonNodeFactory.instance.objectNode()
                    .put("category", "THREADING")
                    .put("minimumSeverity", "LOW")
                    .put("reliability", "")
                    .put("textContains", "blocked")
                    .put("limit", 10)),
            new ToolCall(
                "premature-conclusion",
                InvestigationOrchestrator.CONCLUDE_TOOL,
                JsonNodeFactory.instance.objectNode().put("answer", "Not evidence-backed yet."))));
      }
      assertThat(previous).singleElement()
          .extracting(execution -> execution.result().status())
          .isEqualTo(ToolStatus.SUCCESS);
      return new InvestigationTurn("", List.of(new ToolCall(
          "call-2",
          InvestigationOrchestrator.CONCLUDE_TOOL,
          JsonNodeFactory.instance.objectNode()
              .put("answer", "The dump shows blocked worker threads."))));
    });

    InvestigationResultFixture fixture = fixture();
    var result = new InvestigationOrchestrator(
        new DiagnosticToolRegistry(List.of(new SearchFindingsTool())))
        .investigate(fixture.context(), model);

    assertThat(result.status()).isEqualTo(InvestigationStatus.CONCLUDED);
    assertThat(result.answer()).isEqualTo("The dump shows blocked worker threads.");
    assertThat(result.toolTraces()).singleElement()
        .extracting(trace -> trace.toolName())
        .isEqualTo("search_findings");
  }

  @Test
  void reportsUnavailableModelWithoutExecutingSession() {
    InvestigationModel unavailable = new InvestigationModel() {
      @Override
      public boolean isAvailable() {
        return false;
      }

      @Override
      public InvestigationSession openSession(InvestigationRequest request) {
        throw new AssertionError("session must not be opened");
      }
    };
    var result = new InvestigationOrchestrator(new DiagnosticToolRegistry(List.of()))
        .investigate(fixture().context(), unavailable);

    assertThat(result.status()).isEqualTo(InvestigationStatus.MODEL_UNAVAILABLE);
    assertThat(result.toolTraces()).isEmpty();
  }

  private static InvestigationModel model(InvestigationSession session) {
    return new InvestigationModel() {
      @Override
      public boolean isAvailable() {
        return true;
      }

      @Override
      public InvestigationSession openSession(InvestigationRequest request) {
        assertThat(request.tools())
            .extracting(ToolDefinition::name)
            .contains(
                "search_findings",
                InvestigationOrchestrator.CONCLUDE_TOOL,
                InvestigationOrchestrator.INSUFFICIENT_TOOL);
        assertThat(request.initialPrompt())
            .contains("artifact-1")
            .contains("\"total\":1")
            .contains("\"THREADING\":1")
            .doesNotContain("/secret/production/threads.txt")
            .doesNotContain("Blocked workers")
            .doesNotContain("Several worker threads are blocked.")
            .doesNotContain("secret-finding-value");
        return session;
      }
    };
  }

  private static InvestigationResultFixture fixture() {
    Finding finding = new Finding(
        "finding-1",
        FindingSeverity.HIGH,
        FindingCategory.THREADING,
        "Blocked workers",
        "Several worker threads are blocked.",
        0.9,
        List.of("artifact-1"),
        List.of(),
        List.of(),
        Map.of("internal", "secret-finding-value"),
        FindingReliability.FACT);
    DiagnosticReport report = new DiagnosticReport(
        "report-1",
        Instant.parse("2026-01-01T00:00:00Z"),
        List.of(new Artifact(
            "artifact-1",
            ArtifactType.THREAD_DUMP,
            Instant.parse("2026-01-01T00:00:00Z"),
            Map.of(),
            JvmFlavour.HOTSPOT,
            Path.of("/secret/production/threads.txt"))),
        List.of(finding),
        List.of(),
        CorrelationSummary.empty(),
        JvmProfile.unknown(),
        null);
    return new InvestigationResultFixture(new InvestigationContext(
        "Why is the JVM unresponsive?",
        report,
        List.of()));
  }

  private record InvestigationResultFixture(InvestigationContext context) {}
}
