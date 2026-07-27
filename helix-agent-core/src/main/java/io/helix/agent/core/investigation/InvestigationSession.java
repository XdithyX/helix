package io.helix.agent.core.investigation;

import java.util.List;

public interface InvestigationSession {
  InvestigationTurn next(List<ToolExecution> previousExecutions);
}
