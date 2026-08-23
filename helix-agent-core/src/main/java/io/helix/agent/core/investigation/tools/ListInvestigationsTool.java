package io.helix.agent.core.investigation.tools;

import io.helix.agent.core.investigation.DiagnosticTool;
import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.agent.core.investigation.ToolDefinition;
import io.helix.agent.core.investigation.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Returns a bounded list of investigations from the workspace.
 *
 * <p>The investigation data is supplied via a {@link Supplier} injected at
 * construction time so this tool remains independent of the storage module
 * (avoiding a circular dependency). The supplier must return pre-serialized
 * summary maps; it must never return raw artifact bytes.
 *
 * <p>This tool is only registered in workspace mode; static-artifact mode does
 * not register it.
 */
public final class ListInvestigationsTool
    implements DiagnosticTool<ListInvestigationsTool.Input> {

  /** No arguments — list_investigations needs no parameters. */
  public record Input() {}

  private final Supplier<List<Map<String, Object>>> investigationsSupplier;

  public ListInvestigationsTool(Supplier<List<Map<String, Object>>> investigationsSupplier) {
    this.investigationsSupplier =
        Objects.requireNonNull(investigationsSupplier, "investigationsSupplier");
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        "list_investigations",
        "List saved investigations in the workspace. Returns ID, name, status, "
            + "creation time, and artifact types. Call this first to find the "
            + "investigationId required by the evidence query tools.",
        ToolSchemas.object(Map.of()));
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public ToolResult execute(Input input, InvestigationContext context) {
    List<Map<String, Object>> investigations = investigationsSupplier.get();
    return ToolResult.success(
        "Found " + investigations.size() + " investigation(s) in the workspace.",
        Map.of("investigations", investigations),
        List.of(),
        false);
  }
}
