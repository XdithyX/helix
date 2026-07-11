package io.helix.agent.core;

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

public final class AgentRegistry {
  private final List<DiagnosticAgent> agents;

  public AgentRegistry(List<DiagnosticAgent> agents) {
    this.agents = agents == null ? List.of() : List.copyOf(agents);
  }

  public static AgentRegistry load() {
    List<DiagnosticAgent> loaded = ServiceLoader.load(DiagnosticAgent.class).stream()
        .map(ServiceLoader.Provider::get)
        .sorted(Comparator.comparing(DiagnosticAgent::agentId))
        .toList();
    return new AgentRegistry(loaded);
  }

  public List<DiagnosticAgent> agents() {
    return agents;
  }

  public List<DiagnosticAgent> domainAgents() {
    return agents.stream()
        .filter(agent -> !(agent instanceof CorrelationDiagnosticAgent))
        .filter(agent -> !(agent instanceof NarrativeDiagnosticAgent))
        .toList();
  }

  public List<CorrelationDiagnosticAgent> correlationAgents() {
    return agents.stream()
        .filter(CorrelationDiagnosticAgent.class::isInstance)
        .map(CorrelationDiagnosticAgent.class::cast)
        .toList();
  }

  public List<NarrativeDiagnosticAgent> narrativeAgents() {
    return agents.stream()
        .filter(NarrativeDiagnosticAgent.class::isInstance)
        .map(NarrativeDiagnosticAgent.class::cast)
        .toList();
  }
}
