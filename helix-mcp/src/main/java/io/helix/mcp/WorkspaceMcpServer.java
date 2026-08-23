package io.helix.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.helix.agent.core.AgentJson;
import io.helix.agent.core.investigation.DiagnosticToolRegistry;
import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.agent.core.investigation.ToolCall;
import io.helix.agent.core.investigation.ToolDefinition;
import io.helix.agent.core.investigation.ToolExecution;
import io.helix.agent.core.investigation.ToolResult;
import io.helix.agent.core.investigation.ToolStatus;
import io.helix.agent.core.investigation.tools.DefaultDiagnosticTools;
import io.helix.agent.core.investigation.tools.ListInvestigationsTool;
import io.helix.investigations.InvestigationId;
import io.helix.investigations.InvestigationStatus;
import io.helix.investigations.InvestigationSummary;
import io.helix.investigations.LocalWorkspaceInvestigationStore;
import io.helix.investigations.StoredInvestigation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MCP server for workspace mode ({@code --workspace}).
 *
 * <p>The six existing evidence tools are re-exposed with an additional required
 * {@code investigationId} property injected into their JSON schema. When a call
 * arrives, this server:
 * <ol>
 *   <li>Reads and validates {@code investigationId} from {@code arguments}.
 *   <li>Loads the matching {@link StoredInvestigation} from the store.
 *   <li>Removes {@code investigationId} from {@code arguments} before binding
 *       to the existing tool's {@code Input} record.
 *   <li>Delegates to {@link DiagnosticToolRegistry#execute} with the loaded
 *       {@link InvestigationContext}.
 * </ol>
 *
 * <p>{@code list_investigations} is registered without an {@code investigationId}
 * requirement. Static-artifact mode is handled by the existing
 * {@link HelixMcpServer}.
 */
final class WorkspaceMcpServer {

  /**
   * The six evidence tools that require investigationId in workspace mode.
   * list_investigations does not require an ID.
   */
  private static final Set<String> EVIDENCE_TOOLS = Set.of(
      "list_artifacts",
      "search_findings",
      "query_threads",
      "query_jfr",
      "query_gc",
      "query_heap");

  static final String INVESTIGATION_ID_FIELD = "investigationId";

  private final LocalWorkspaceInvestigationStore store;
  private final DiagnosticToolRegistry registry;
  private final ObjectMapper objectMapper = new ObjectMapper();

  WorkspaceMcpServer(Path workspacePath) {
    this(new LocalWorkspaceInvestigationStore(workspacePath), DefaultDiagnosticTools.create());
  }

  WorkspaceMcpServer(LocalWorkspaceInvestigationStore store, DiagnosticToolRegistry registry) {
    this.store = store;
    this.registry = registry;
  }

  McpSyncServer start() {
    StdioServerTransportProvider transport =
        new StdioServerTransportProvider(McpJsonDefaults.getMapper());
    return start(transport);
  }

  McpSyncServer start(StdioServerTransportProvider transport) {
    return McpServer.sync(transport)
        .serverInfo("helix-mcp", HelixMcpServer.serverVersion())
        .instructions(HelixMcpServer.SERVER_INSTRUCTIONS)
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .tools(toolSpecifications())
        .build();
  }

  List<SyncToolSpecification> toolSpecifications() {
    List<SyncToolSpecification> specs = new ArrayList<>();

    // list_investigations — no investigationId required
    ListInvestigationsTool listTool = new ListInvestigationsTool(this::investigationSummaries);
    ToolDefinition listDef = listTool.definition();
    McpSchema.Tool listMcpTool = McpSchema.Tool.builder(listDef.name(), listDef.parametersJsonSchema())
        .description(listDef.description())
        .annotations(readOnlyAnnotations(listDef.name()))
        .build();
    specs.add(SyncToolSpecification.builder()
        .tool(listMcpTool)
        .callHandler((exchange, request) -> executeListInvestigations(request.arguments()))
        .build());

    // Six evidence tools — inject required investigationId into their schema
    for (ToolDefinition def : registry.definitions()) {
      if (!EVIDENCE_TOOLS.contains(def.name())) {
        continue;
      }
      Map<String, Object> augmentedSchema = injectInvestigationId(def.parametersJsonSchema());
      McpSchema.Tool mcpTool = McpSchema.Tool.builder(def.name(), augmentedSchema)
          .description(def.description())
          .annotations(readOnlyAnnotations(def.name()))
          .build();
      specs.add(SyncToolSpecification.builder()
          .tool(mcpTool)
          .callHandler((exchange, request) -> executeEvidenceTool(def, request.arguments()))
          .build());
    }
    return specs;
  }

  // ---- dispatch ----

  private McpSchema.CallToolResult executeListInvestigations(Map<String, Object> arguments) {
    // list_investigations has an empty Input record — no arguments needed
    InvestigationContext placeholder = emptyContext();
    ToolCall call = new ToolCall("", "list_investigations",
        objectMapper.valueToTree(Map.of()));
    // Execute directly through the list tool (registry does not hold it)
    ListInvestigationsTool tool = new ListInvestigationsTool(this::investigationSummaries);
    ToolResult result = tool.execute(new ListInvestigationsTool.Input(), placeholder);
    return toMcpResult(result);
  }

  private McpSchema.CallToolResult executeEvidenceTool(
      ToolDefinition definition,
      Map<String, Object> arguments) {

    Map<String, Object> args = arguments == null ? Map.of() : arguments;

    // 1. Extract and validate investigationId
    Object rawId = args.get(INVESTIGATION_ID_FIELD);
    if (rawId == null || rawId.toString().isBlank()) {
      return toMcpResult(ToolResult.invalid(
          "investigationId is required in workspace mode."));
    }
    String idValue = rawId.toString().trim();

    InvestigationId id;
    try {
      id = new InvestigationId(idValue);
    } catch (IllegalArgumentException e) {
      return toMcpResult(ToolResult.invalid("Invalid investigationId: " + idValue));
    }

    // 2. Load the ready investigation
    Optional<StoredInvestigation> stored;
    try {
      stored = store.find(id);
    } catch (Exception e) {
      return toMcpResult(ToolResult.error("Failed to load investigation: " + safeMessage(e)));
    }
    if (stored.isEmpty()) {
      // Distinguish unknown vs non-ready by checking summaries
      Optional<InvestigationSummary> summary = store.list().stream()
          .filter(s -> s.id().equals(id))
          .findFirst();
      if (summary.isEmpty()) {
        return toMcpResult(ToolResult.invalid(
            "Unknown investigationId: " + idValue));
      }
      InvestigationStatus status = summary.get().status();
      return toMcpResult(ToolResult.unavailable(
          "Investigation " + idValue + " is not queryable (status: " + status + ").",
          "Only READY investigations can be queried. "
              + (status == InvestigationStatus.FAILED
              ? "The import failed: " + summary.get().failureReason()
              : "Wait for the import to complete.")));
    }

    // 3. Remove investigationId from args before binding to Input
    Map<String, Object> strippedArgs = new LinkedHashMap<>(args);
    strippedArgs.remove(INVESTIGATION_ID_FIELD);

    // 4. Execute via registry with the loaded context
    InvestigationContext context = stored.get().context();
    ToolCall call = new ToolCall("", definition.name(),
        objectMapper.valueToTree(strippedArgs));
    ToolExecution execution = registry.execute(call, context);
    return toMcpResult(execution.result());
  }

  // ---- helpers ----

  /**
   * Inject a required {@code investigationId} string property into an existing
   * object schema. Returns a new map; never mutates the original.
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> injectInvestigationId(Map<String, Object> originalSchema) {
    Map<String, Object> schema = new LinkedHashMap<>(originalSchema);
    // Copy existing properties and add investigationId
    Object existingProps = originalSchema.get("properties");
    Map<String, Object> properties = new LinkedHashMap<>();
    if (existingProps instanceof Map<?, ?> m) {
      properties.putAll((Map<String, Object>) m);
    }
    properties.put(INVESTIGATION_ID_FIELD, Map.of(
        "type", "string",
        "description", "Required. The investigation ID returned by list_investigations."));
    schema.put("properties", properties);

    // Add to required array if the schema supports it
    Object existingRequired = originalSchema.get("required");
    List<String> required = new ArrayList<>();
    if (existingRequired instanceof List<?> list) {
      for (Object item : list) {
        required.add(item.toString());
      }
    }
    if (!required.contains(INVESTIGATION_ID_FIELD)) {
      required.add(INVESTIGATION_ID_FIELD);
    }
    schema.put("required", List.copyOf(required));
    return Map.copyOf(schema);
  }

  private McpSchema.CallToolResult toMcpResult(ToolResult toolResult) {
    Map<String, Object> result = registry.resultAsMap(toolResult);
    boolean isError = toolResult.status() == ToolStatus.ERROR
        || toolResult.status() == ToolStatus.INVALID_ARGUMENTS;
    return McpSchema.CallToolResult.builder()
        .addTextContent(AgentJson.toJson(result))
        .structuredContent(result)
        .isError(isError)
        .build();
  }

  private List<Map<String, Object>> investigationSummaries() {
    return store.list().stream()
        .map(s -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", s.id().value());
          row.put("name", s.name());
          row.put("status", s.status().name());
          row.put("createdAt", s.createdAt().toString());
          row.put("artifactTypes", s.artifactTypes().stream()
              .map(Enum::name).toList());
          row.put("correlate", s.correlate());
          if (!s.failureReason().isBlank()) {
            row.put("failureReason", s.failureReason());
          }
          return (Map<String, Object>) row;
        })
        .toList();
  }

  /** Empty placeholder context for list_investigations (no artifact access needed). */
  private static InvestigationContext emptyContext() {
    return new io.helix.agent.core.investigation.InvestigationContext(
        "",
        new io.helix.core.model.DiagnosticReport(
            "workspace-placeholder",
            java.time.Instant.now(),
            List.of(), List.of(), List.of(),
            io.helix.core.model.CorrelationSummary.empty(),
            io.helix.core.model.JvmProfile.unknown(),
            null),
        List.of());
  }

  private static McpSchema.ToolAnnotations readOnlyAnnotations(String title) {
    return McpSchema.ToolAnnotations.builder()
        .title(title)
        .readOnlyHint(true)
        .destructiveHint(false)
        .idempotentHint(true)
        .openWorldHint(false)
        .build();
  }

  private static String safeMessage(Throwable t) {
    String msg = t.getMessage();
    return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
  }
}
