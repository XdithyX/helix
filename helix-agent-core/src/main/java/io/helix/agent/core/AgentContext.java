package io.helix.agent.core;

import io.helix.core.model.FrameworkContext;
import io.helix.core.model.JvmProfile;
import io.helix.core.spi.ReasoningProvider;
import java.util.List;
import java.util.Map;

public record AgentContext(
    JvmProfile jvmProfile,
    FrameworkContext frameworkContext,
    ReasoningProvider reasoningProvider,
    List<AgentResult> agentResults,
    CorrelatedDiagnosis correlatedDiagnosis,
    Map<String, Object> attributes
) {
  public AgentContext {
    jvmProfile = jvmProfile == null ? JvmProfile.unknown() : jvmProfile;
    frameworkContext = frameworkContext == null ? unknownFrameworkContext() : frameworkContext;
    agentResults = agentResults == null ? List.of() : List.copyOf(agentResults);
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  public static AgentContext defaults() {
    return new AgentContext(JvmProfile.unknown(), unknownFrameworkContext(), null, List.of(), null, Map.of());
  }

  public AgentContext withAgentResults(List<AgentResult> results) {
    return new AgentContext(jvmProfile, frameworkContext, reasoningProvider, results, correlatedDiagnosis, attributes);
  }

  public AgentContext withCorrelatedDiagnosis(CorrelatedDiagnosis diagnosis) {
    return new AgentContext(jvmProfile, frameworkContext, reasoningProvider, agentResults, diagnosis, attributes);
  }

  public String frameworkName() {
    return frameworkContext.frameworkName();
  }

  private static FrameworkContext unknownFrameworkContext() {
    return new FrameworkContext("unknown", "", Map.of());
  }
}
