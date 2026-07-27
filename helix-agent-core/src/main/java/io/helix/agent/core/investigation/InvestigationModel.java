package io.helix.agent.core.investigation;

public interface InvestigationModel {
  boolean isAvailable();

  InvestigationSession openSession(InvestigationRequest request);
}
