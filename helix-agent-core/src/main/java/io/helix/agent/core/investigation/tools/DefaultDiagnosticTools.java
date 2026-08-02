package io.helix.agent.core.investigation.tools;

import io.helix.agent.core.investigation.DiagnosticToolRegistry;
import java.util.List;

public final class DefaultDiagnosticTools {
  private DefaultDiagnosticTools() {}

  public static DiagnosticToolRegistry create() {
    return new DiagnosticToolRegistry(List.of(
        new ListArtifactsTool(),
        new SearchFindingsTool(),
        new QueryThreadsTool(),
        new QueryJfrTool(),
        new QueryGcTool(),
        new QueryHeapTool()));
  }
}
