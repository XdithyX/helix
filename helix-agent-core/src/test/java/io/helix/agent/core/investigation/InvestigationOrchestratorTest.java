package io.helix.agent.core.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.helix.core.model.ParsedArtifact;
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

  @Test
  void rejectsPrematureOomConclusionUntilAvailableEvidenceIsExamined() {
    AtomicInteger turns = new AtomicInteger();
    InvestigationModel model = new InvestigationModel() {
      @Override
      public boolean isAvailable() {
        return true;
      }

      @Override
      public InvestigationSession openSession(InvestigationRequest request) {
        assertThat(request.initialPrompt())
            .contains("query_heap", "query_jfr", "query_gc");
        return previous -> switch (turns.getAndIncrement()) {
          case 0 -> new InvestigationTurn("", List.of(new ToolCall(
              "conclude-early",
              InvestigationOrchestrator.CONCLUDE_TOOL,
              JsonNodeFactory.instance.objectNode().put("answer", "It was a thread leak."))));
          case 1 -> {
            assertThat(previous).singleElement().satisfies(execution -> {
              assertThat(execution.call().name()).isEqualTo(InvestigationOrchestrator.CONCLUDE_TOOL);
              assertThat(execution.result().status()).isEqualTo(ToolStatus.INVALID_ARGUMENTS);
              assertThat(execution.result().summary()).contains("query_heap", "query_jfr", "query_gc");
            });
            yield new InvestigationTurn("", List.of(
                call("heap", "query_heap"),
                call("jfr", "query_jfr"),
                call("gc", "query_gc")));
          }
          default -> new InvestigationTurn("", List.of(new ToolCall(
              "conclude-supported",
              InvestigationOrchestrator.CONCLUDE_TOOL,
              JsonNodeFactory.instance.objectNode().put(
                  "answer",
                  "Large retained arrays exhausted the heap; JFR and GC evidence corroborate it."))));
        };
      }
    };
    InvestigationContext context = oomContext();
    DiagnosticToolRegistry registry = new DiagnosticToolRegistry(List.of(
        successfulTool("query_heap"),
        successfulTool("query_jfr"),
        successfulTool("query_gc")));

    var result = new InvestigationOrchestrator(registry).investigate(context, model);

    assertThat(result.status()).isEqualTo(InvestigationStatus.CONCLUDED);
    assertThat(result.answer()).contains("Large retained arrays");
    assertThat(result.toolTraces())
        .extracting(trace -> trace.toolName())
        .containsExactly("query_heap", "query_jfr", "query_gc");
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

  private static ToolCall call(String id, String name) {
    return new ToolCall(id, name, JsonNodeFactory.instance.objectNode());
  }

  private static DiagnosticTool<JsonNode> successfulTool(String name) {
    return new DiagnosticTool<>() {
      @Override
      public ToolDefinition definition() {
        return new ToolDefinition(name, "Test tool.", Map.of("type", "object"));
      }

      @Override
      public Class<JsonNode> inputType() {
        return JsonNode.class;
      }

      @Override
      public ToolResult execute(JsonNode input, InvestigationContext context) {
        return ToolResult.success(
            "Evidence returned.",
            Map.of(),
            List.of(new EvidenceReference(
                "artifact-1", Instant.parse("2026-01-01T00:00:00Z"), "test:evidence")),
            false);
      }
    };
  }

  private static InvestigationContext oomContext() {
    List<Artifact> artifacts = List.of(
        artifact("heap", ArtifactType.HEAP_DUMP),
        artifact("jfr", ArtifactType.JFR),
        artifact("gc", ArtifactType.GC_LOG));
    DiagnosticReport report = new DiagnosticReport(
        "oom-report",
        Instant.parse("2026-01-01T00:00:00Z"),
        artifacts,
        List.of(),
        List.of(),
        CorrelationSummary.empty(),
        JvmProfile.unknown(),
        null);
    List<ParsedArtifact> parsed = artifacts.stream()
        .map(artifact -> new ParsedArtifact(
            artifact,
            List.of(),
            Map.of("normalized", true),
            List.of()))
        .toList();
    return new InvestigationContext("Why did this JVM run out of memory?", report, parsed);
  }

  private static Artifact artifact(String id, ArtifactType type) {
    return new Artifact(
        id,
        type,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of(),
        JvmFlavour.HOTSPOT,
        Path.of("/secret/" + id));
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
