package io.helix.agent.core;

import io.helix.core.model.CorrelatedEvent;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class AgentOrchestrator {
  private final AgentRegistry registry;

  public AgentOrchestrator() {
    this(AgentRegistry.load());
  }

  public AgentOrchestrator(AgentRegistry registry) {
    this.registry = registry;
  }

  public DiagnosticReport analyze(DiagnosticReport report, AgentContext context) {
    AgentContext safeContext = context == null ? AgentContext.defaults() : context;
    List<AgentResult> domainResults = runDomainAgents(report, safeContext);
    AgentContext withResults = safeContext.withAgentResults(domainResults);

    CorrelatedDiagnosis diagnosis = runCorrelation(report, withResults);
    AgentContext withCorrelation = withResults.withCorrelatedDiagnosis(diagnosis);
    NarrativeResult narrative = runNarrative(report, domainResults, diagnosis, withCorrelation);

    List<Finding> finalFindings = narrative == null
        ? mergeFindings(report.findings(), domainResults)
        : narrative.prioritizedFindings();
    CorrelationSummary correlationSummary = mergeCorrelationSummary(report, diagnosis);
    String llmNarrative = narrative == null ? report.llmNarrative() : narrative.asReportNarrative();

    return new DiagnosticReport(
        report.reportId(),
        report.generatedAt(),
        report.artifacts(),
        finalFindings,
        report.timeline(),
        correlationSummary,
        report.jvmProfile(),
        llmNarrative,
        report.investigation());
  }

  private List<AgentResult> runDomainAgents(DiagnosticReport report, AgentContext context) {
    List<DiagnosticAgent> agents = registry.domainAgents();
    if (agents.isEmpty()) {
      return List.of();
    }
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<AgentResult>> futures = new ArrayList<>();
      for (DiagnosticAgent agent : agents) {
        Callable<AgentResult> task = () -> agent.analyze(report, context);
        futures.add(executor.submit(task));
      }
      List<AgentResult> results = new ArrayList<>();
      for (Future<AgentResult> future : futures) {
        results.add(future.get());
      }
      return results.stream()
          .sorted(Comparator.comparing(AgentResult::agentId))
          .toList();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Agent execution was interrupted", e);
    } catch (Exception e) {
      throw new IllegalStateException("Agent execution failed", e);
    }
  }

  private CorrelatedDiagnosis runCorrelation(DiagnosticReport report, AgentContext context) {
    List<CorrelationDiagnosticAgent> agents = registry.correlationAgents();
    if (agents.isEmpty()) {
      return CorrelatedDiagnosis.empty();
    }
    return agents.getFirst().correlate(report, context.agentResults(), context);
  }

  private NarrativeResult runNarrative(
      DiagnosticReport report,
      List<AgentResult> results,
      CorrelatedDiagnosis diagnosis,
      AgentContext context) {
    List<NarrativeDiagnosticAgent> agents = registry.narrativeAgents();
    if (agents.isEmpty()) {
      return null;
    }
    return agents.getFirst().narrate(report, results, diagnosis, context);
  }

  private static List<Finding> mergeFindings(List<Finding> original, List<AgentResult> results) {
    Map<String, Finding> byId = new LinkedHashMap<>();
    original.forEach(finding -> byId.put(finding.id(), finding));
    results.stream()
        .flatMap(result -> result.enrichedFindings().stream())
        .forEach(finding -> byId.put(finding.id(), finding));
    return List.copyOf(byId.values());
  }

  private static CorrelationSummary mergeCorrelationSummary(DiagnosticReport report, CorrelatedDiagnosis diagnosis) {
    if (diagnosis == null || diagnosis.links().isEmpty()) {
      return report.correlationSummary();
    }
    List<CorrelatedEvent> correlatedEvents = diagnosis.links().stream()
        .filter(link -> link.primary() != null && link.related() != null)
        .map(link -> new CorrelatedEvent(
            link.ruleName(),
            link.primary(),
            link.related(),
            link.confidence(),
            link.explanation()))
        .toList();
    Map<String, Object> metrics = new LinkedHashMap<>(report.correlationSummary().metrics());
    metrics.put("agentCorrelationConfidence", diagnosis.confidence());
    metrics.put("agentCorrelationLinks", diagnosis.links().size());
    return new CorrelationSummary(diagnosis.narrative(), correlatedEvents, metrics);
  }
}
