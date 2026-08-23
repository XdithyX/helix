package io.helix.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.JvmProfile;
import io.helix.core.model.ParsedArtifact;
import io.helix.investigations.InvestigationId;
import io.helix.investigations.InvestigationSummary;
import io.helix.investigations.LocalWorkspaceInvestigationStore;
import io.helix.investigations.StoredInvestigation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceMcpServerTest {

  @TempDir
  Path workspaceDir;

  // ---- schema: investigationId is injected as required ----

  @Test
  void evidenceToolSchemasRequireInvestigationId() throws Exception {
    WorkspaceMcpServer server = serverWithInvestigation(workspaceDir);

    List<String> evidenceTools = List.of(
        "list_artifacts", "search_findings", "query_threads",
        "query_jfr", "query_gc", "query_heap");

    for (SyncToolSpecification spec : server.toolSpecifications()) {
      if (!evidenceTools.contains(spec.tool().name())) {
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> properties =
          (Map<String, Object>) spec.tool().inputSchema().get("properties");
      assertThat(properties)
          .as("Schema for " + spec.tool().name() + " must contain investigationId")
          .containsKey(WorkspaceMcpServer.INVESTIGATION_ID_FIELD);

      Object required = spec.tool().inputSchema().get("required");
      assertThat(required).isNotNull();
      assertThat(required.toString()).contains(WorkspaceMcpServer.INVESTIGATION_ID_FIELD);
    }
  }

  @Test
  void listInvestigationsToolHasNoInvestigationIdRequirement() throws Exception {
    WorkspaceMcpServer server = serverWithInvestigation(workspaceDir);

    SyncToolSpecification listSpec = server.toolSpecifications().stream()
        .filter(s -> s.tool().name().equals("list_investigations"))
        .findFirst()
        .orElseThrow();

    @SuppressWarnings("unchecked")
    Map<String, Object> properties =
        (Map<String, Object>) listSpec.tool().inputSchema().get("properties");
    assertThat(properties).doesNotContainKey(WorkspaceMcpServer.INVESTIGATION_ID_FIELD);
  }

  // ---- list_investigations returns summaries ----

  @Test
  void listInvestigationsReturnsSavedSummaries() throws Exception {
    InvestigationId id = saveReadyInvestigation(workspaceDir, "checkout-oom");
    WorkspaceMcpServer server = new WorkspaceMcpServer(
        new LocalWorkspaceInvestigationStore(workspaceDir),
        io.helix.agent.core.investigation.tools.DefaultDiagnosticTools.create());

    SyncToolSpecification listSpec = server.toolSpecifications().stream()
        .filter(s -> s.tool().name().equals("list_investigations"))
        .findFirst()
        .orElseThrow();

    McpSchema.CallToolResult result = listSpec.callHandler().apply(
        null,
        McpSchema.CallToolRequest.builder("list_investigations").arguments(Map.of()).build());

    assertThat(result.isError()).isFalse();
    assertThat(result.structuredContent()).isInstanceOfSatisfying(Map.class, structured -> {
      // structuredContent is the full ToolResult map; evidence is nested under "data"
      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) structured.get("data");
      assertThat(data).containsKey("investigations");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> investigations = (List<Map<String, Object>>) data.get("investigations");
      assertThat(investigations).hasSize(1);
      assertThat(investigations.get(0)).containsEntry("id", id.value());
      assertThat(investigations.get(0)).containsEntry("name", "checkout-oom");
      assertThat(investigations.get(0)).containsEntry("status", "READY");
    });
  }

  // ---- missing investigationId returns structured error ----

  @Test
  void missingInvestigationIdReturnsStructuredError() throws Exception {
    WorkspaceMcpServer server = serverWithInvestigation(workspaceDir);
    SyncToolSpecification spec = evidenceToolSpec(server, "list_artifacts");

    McpSchema.CallToolResult result = spec.callHandler().apply(
        null,
        McpSchema.CallToolRequest.builder("list_artifacts")
            .arguments(Map.of()) // no investigationId
            .build());

    assertThat(result.isError()).isTrue();
    assertThat(result.structuredContent().toString()).contains("investigationId");
  }

  // ---- blank investigationId returns structured error ----

  @Test
  void blankInvestigationIdReturnsStructuredError() throws Exception {
    WorkspaceMcpServer server = serverWithInvestigation(workspaceDir);
    SyncToolSpecification spec = evidenceToolSpec(server, "list_artifacts");

    McpSchema.CallToolResult result = spec.callHandler().apply(
        null,
        McpSchema.CallToolRequest.builder("list_artifacts")
            .arguments(Map.of(WorkspaceMcpServer.INVESTIGATION_ID_FIELD, ""))
            .build());

    assertThat(result.isError()).isTrue();
  }

  // ---- unknown investigationId returns structured error ----

  @Test
  void unknownInvestigationIdReturnsStructuredError() throws Exception {
    WorkspaceMcpServer server = serverWithInvestigation(workspaceDir);
    SyncToolSpecification spec = evidenceToolSpec(server, "list_artifacts");

    McpSchema.CallToolResult result = spec.callHandler().apply(
        null,
        McpSchema.CallToolRequest.builder("list_artifacts")
            .arguments(Map.of(WorkspaceMcpServer.INVESTIGATION_ID_FIELD, "inv_doesnotexistXXXX"))
            .build());

    assertThat(result.isError()).isTrue();
    assertThat(result.structuredContent().toString()).contains("Unknown");
  }

  // ---- failed investigationId returns UNAVAILABLE (not fabricated evidence) ----

  @Test
  void failedInvestigationIdReturnsUnavailable() throws Exception {
    LocalWorkspaceInvestigationStore store = new LocalWorkspaceInvestigationStore(workspaceDir);
    InvestigationId id = InvestigationId.generate();
    store.saveFailed(InvestigationSummary.failed(
        id, "broken", Instant.EPOCH, List.of(ArtifactType.THREAD_DUMP), false, "parse error"));

    WorkspaceMcpServer server = new WorkspaceMcpServer(store,
        io.helix.agent.core.investigation.tools.DefaultDiagnosticTools.create());
    SyncToolSpecification spec = evidenceToolSpec(server, "list_artifacts");

    McpSchema.CallToolResult result = spec.callHandler().apply(
        null,
        McpSchema.CallToolRequest.builder("list_artifacts")
            .arguments(Map.of(WorkspaceMcpServer.INVESTIGATION_ID_FIELD, id.value()))
            .build());

    assertThat(result.isError()).isFalse(); // UNAVAILABLE is not an error
    assertThat(result.structuredContent().toString()).contains("UNAVAILABLE");
  }

  // ---- two different IDs return evidence from their own investigation only ----

  @Test
  void twoInvestigationsDoNotCrossContaminate() throws Exception {
    InvestigationId id1 = saveReadyInvestigation(workspaceDir, "inv-alpha");
    InvestigationId id2 = saveReadyInvestigation(workspaceDir, "inv-beta");

    WorkspaceMcpServer server = new WorkspaceMcpServer(
        new LocalWorkspaceInvestigationStore(workspaceDir),
        io.helix.agent.core.investigation.tools.DefaultDiagnosticTools.create());

    SyncToolSpecification spec = evidenceToolSpec(server, "list_artifacts");

    McpSchema.CallToolResult result1 = spec.callHandler().apply(null,
        McpSchema.CallToolRequest.builder("list_artifacts")
            .arguments(Map.of(WorkspaceMcpServer.INVESTIGATION_ID_FIELD, id1.value()))
            .build());

    McpSchema.CallToolResult result2 = spec.callHandler().apply(null,
        McpSchema.CallToolRequest.builder("list_artifacts")
            .arguments(Map.of(WorkspaceMcpServer.INVESTIGATION_ID_FIELD, id2.value()))
            .build());

    assertThat(result1.isError()).isFalse();
    assertThat(result2.isError()).isFalse();

    // Each result must reference only its own artifact ID
    String text1 = result1.structuredContent().toString();
    String text2 = result2.structuredContent().toString();
    assertThat(text1).contains(id1.value());
    assertThat(text2).contains(id2.value());
    // Crucially: result1 must not reference id2's artifact and vice versa
    assertThat(text1).doesNotContain(id2.value());
    assertThat(text2).doesNotContain(id1.value());
  }

  // ---- workspace mode does not invoke parsers/analyzers again ----

  @Test
  void workspaceModeDoesNotInvokeParsers() throws Exception {
    // Save an investigation, then start a NEW store instance (simulating IDE restart).
    InvestigationId id = saveReadyInvestigation(workspaceDir, "restart-test");

    // New server instance — no analysis thread, no parsers
    WorkspaceMcpServer server = new WorkspaceMcpServer(
        new LocalWorkspaceInvestigationStore(workspaceDir),
        io.helix.agent.core.investigation.tools.DefaultDiagnosticTools.create());

    SyncToolSpecification spec = evidenceToolSpec(server, "search_findings");

    McpSchema.CallToolResult result = spec.callHandler().apply(null,
        McpSchema.CallToolRequest.builder("search_findings")
            .arguments(Map.of(WorkspaceMcpServer.INVESTIGATION_ID_FIELD, id.value()))
            .build());

    // Result is SUCCESS (evidence was loaded from disk, no parsing happened)
    assertThat(result.isError()).isFalse();
    assertThat(result.structuredContent().toString()).contains("SUCCESS");
  }

  // ---- SDK accepts all workspace tool schemas ----

  @Test
  void officialSdkAcceptsAllWorkspaceToolSchemas() throws Exception {
    saveReadyInvestigation(workspaceDir, "schema-test");
    WorkspaceMcpServer helixServer = new WorkspaceMcpServer(
        new LocalWorkspaceInvestigationStore(workspaceDir),
        io.helix.agent.core.investigation.tools.DefaultDiagnosticTools.create());

    StdioServerTransportProvider transport = new StdioServerTransportProvider(
        McpJsonDefaults.getMapper(),
        new ByteArrayInputStream(new byte[0]),
        new ByteArrayOutputStream());

    McpSyncServer server = helixServer.start(transport);
    assertThat(server.listTools()).allSatisfy(tool -> {
      assertThat(tool.annotations().readOnlyHint()).isTrue();
      assertThat(tool.annotations().destructiveHint()).isFalse();
    });
    server.close();
  }

  // ---- --workspace arg parsing ----

  @Test
  void workspaceArgParsing() {
    HelixMcpMain.LaunchOptions options = HelixMcpMain.parseArguments(new String[] {
        "--workspace", workspaceDir.toString()
    });
    assertThat(options.workspacePath()).isEqualTo(workspaceDir.toAbsolutePath().normalize());
    assertThat(options.artifactPaths()).isEmpty();
    assertThat(options.correlate()).isFalse();
  }

  @Test
  void workspaceAndArtifactPathsAreMutuallyExclusive() {
    assertThatThrownBy(() -> HelixMcpMain.parseArguments(new String[] {
        "--workspace", workspaceDir.toString(), "artifact.txt"
    })).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mutually exclusive");
  }

  @Test
  void workspaceAndCorrelateAreMutuallyExclusive() {
    assertThatThrownBy(() -> HelixMcpMain.parseArguments(new String[] {
        "--workspace", workspaceDir.toString(), "--correlate"
    })).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("incompatible");
  }

  // ---- helpers ----

  private WorkspaceMcpServer serverWithInvestigation(Path workspace) throws IOException {
    saveReadyInvestigation(workspace, "default");
    return new WorkspaceMcpServer(
        new LocalWorkspaceInvestigationStore(workspace),
        io.helix.agent.core.investigation.tools.DefaultDiagnosticTools.create());
  }

  private InvestigationId saveReadyInvestigation(Path workspace, String name) throws IOException {
    LocalWorkspaceInvestigationStore store = new LocalWorkspaceInvestigationStore(workspace);
    InvestigationId id = InvestigationId.generate();
    InvestigationContext context = minimalContext(id.value(), name);
    store.saveReady(id, name, Instant.EPOCH, false, context);
    return id;
  }

  private static InvestigationContext minimalContext(String artifactId, String reportSuffix) {
    Artifact artifact = new Artifact(
        artifactId,
        ArtifactType.THREAD_DUMP,
        Instant.EPOCH,
        Map.of(),
        JvmFlavour.UNKNOWN,
        Path.of("/tmp/" + artifactId + ".txt"));
    ParsedArtifact parsed = new ParsedArtifact(artifact, List.of(), Map.of(), List.of());
    DiagnosticReport report = new DiagnosticReport(
        "report-" + reportSuffix,
        Instant.EPOCH,
        List.of(artifact),
        List.of(),
        List.of(),
        CorrelationSummary.empty(),
        JvmProfile.unknown(),
        null);
    return new InvestigationContext("", report, List.of(parsed));
  }

  private static SyncToolSpecification evidenceToolSpec(WorkspaceMcpServer server, String name) {
    return server.toolSpecifications().stream()
        .filter(s -> s.tool().name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Tool not found: " + name));
  }
}
