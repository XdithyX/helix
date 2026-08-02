package io.helix.agent.core.investigation.tools;

import io.helix.agent.core.investigation.DiagnosticTool;
import io.helix.agent.core.investigation.EvidenceReference;
import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.agent.core.investigation.ToolDefinition;
import io.helix.agent.core.investigation.ToolResult;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.gc.GcEvent;
import io.helix.core.model.gc.GcLogData;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class QueryGcTool implements DiagnosticTool<QueryGcTool.Input> {
  public record Input(
      String operation,
      String from,
      String until,
      String causeContains,
      String collector,
      String fullGc,
      double minimumDurationMillis,
      int limit
  ) {
    public Input {
      operation = normalize(operation, "SUMMARY");
      from = clean(from);
      until = clean(until);
      causeContains = clean(causeContains);
      collector = normalize(collector, "");
      fullGc = normalize(fullGc, "ANY");
      minimumDurationMillis = Math.max(0, minimumDurationMillis);
      limit = limit <= 0 ? 20 : Math.min(limit, 100);
    }
  }

  private record EventRef(String artifactId, GcEvent event) {}

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        "query_gc",
        "Query normalized GC logs for event details, pause and heap summaries, causes, and heap occupancy trends.",
        ToolSchemas.object(Map.of(
            "operation", ToolSchemas.enumeration(
                "Result shape.", "SUMMARY", "EVENTS", "CAUSE_BREAKDOWN", "HEAP_TREND"),
            "from", ToolSchemas.string("Inclusive ISO-8601 start time, or empty."),
            "until", ToolSchemas.string("Inclusive ISO-8601 end time, or empty."),
            "causeContains", ToolSchemas.string("Case-insensitive GC cause filter."),
            "collector", ToolSchemas.string("Exact collector name such as G1 or ZGC, or empty."),
            "fullGc", ToolSchemas.enumeration("Full-GC filter.", "ANY", "TRUE", "FALSE"),
            "minimumDurationMillis", ToolSchemas.number("Minimum event duration in milliseconds.", 0),
            "limit", ToolSchemas.integer("Maximum events or groups to return.", 1, 100))));
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public ToolResult execute(Input input, InvestigationContext context) {
    Instant from;
    Instant until;
    try {
      from = parseInstant(input.from());
      until = parseInstant(input.until());
    } catch (DateTimeParseException e) {
      return ToolResult.invalid("GC time filters must be ISO-8601 instants: " + e.getParsedString());
    }
    if (from != null && until != null && from.isAfter(until)) {
      return ToolResult.invalid("'from' must not be after 'until'.");
    }
    if (!List.of("ANY", "TRUE", "FALSE").contains(input.fullGc())) {
      return ToolResult.invalid("Unsupported fullGc filter: " + input.fullGc());
    }

    List<EventRef> available = events(context);
    if (available.isEmpty()) {
      return ToolResult.unavailable(
          "No normalized GC-log data is available.",
          "Supply a supported GC log.");
    }
    List<EventRef> matched = available.stream()
        .filter(ref -> matches(ref.event(), input, from, until))
        .toList();
    return switch (input.operation()) {
      case "SUMMARY" -> summary(matched);
      case "EVENTS" -> eventRows(matched, input.limit());
      case "CAUSE_BREAKDOWN" -> causeBreakdown(matched, input.limit());
      case "HEAP_TREND" -> heapTrend(matched, input.limit());
      default -> ToolResult.invalid("Unsupported GC operation: " + input.operation());
    };
  }

  private static ToolResult summary(List<EventRef> matched) {
    List<GcEvent> events = matched.stream().map(EventRef::event).toList();
    List<GcEvent> pauses = events.stream().filter(GcEvent::pause).toList();
    double totalPauseMillis = pauses.stream().mapToDouble(GcEvent::durationMillis).sum();
    double maxPauseMillis = pauses.stream().mapToDouble(GcEvent::durationMillis).max().orElse(0);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("eventCount", events.size());
    data.put("pauseCount", pauses.size());
    data.put("fullGcCount", events.stream().filter(GcEvent::fullGc).count());
    data.put("systemGcCount", events.stream().filter(GcEvent::systemGc).count());
    data.put("humongousAllocationCount", events.stream().filter(GcEvent::humongousAllocation).count());
    data.put("totalPauseMillis", totalPauseMillis);
    data.put("maxPauseMillis", maxPauseMillis);
    events.stream().findFirst().ifPresent(event -> data.put("firstEventAt", event.timestamp().toString()));
    events.stream().reduce((left, right) -> right).ifPresent(event -> {
      data.put("lastEventAt", event.timestamp().toString());
      putIfPresent(data, "latestHeapAfterMb", event.heapAfterMb());
      putIfPresent(data, "latestHeapCommittedMb", event.heapCommittedMb());
    });
    return ToolResult.success(
        "Summarized " + events.size() + " matching GC event(s).",
        data,
        evidence(matched.stream().limit(20).toList()),
        matched.size() > 20);
  }

  private static ToolResult eventRows(List<EventRef> matched, int limit) {
    List<EventRef> returned = matched.stream().limit(limit).toList();
    return ToolResult.success(
        "Matched " + matched.size() + " GC event(s); returning " + returned.size() + ".",
        Map.of("totalMatches", matched.size(), "events", returned.stream()
            .map(QueryGcTool::eventSummary)
            .toList()),
        evidence(returned),
        matched.size() > returned.size());
  }

  private static ToolResult causeBreakdown(List<EventRef> matched, int limit) {
    Map<String, Long> counts = new LinkedHashMap<>();
    matched.forEach(ref -> counts.merge(
        ref.event().cause().isBlank() ? "<unknown>" : ref.event().cause(),
        1L,
        Long::sum));
    List<Map<String, Object>> rows = counts.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .limit(limit)
        .map(entry -> Map.<String, Object>of("cause", entry.getKey(), "count", entry.getValue()))
        .toList();
    return ToolResult.success(
        "Grouped " + matched.size() + " GC event(s) into " + counts.size() + " cause(s).",
        Map.of("totalGroups", counts.size(), "causes", rows),
        evidence(matched.stream().limit(20).toList()),
        counts.size() > rows.size());
  }

  private static ToolResult heapTrend(List<EventRef> matched, int limit) {
    List<EventRef> withHeap = matched.stream()
        .filter(ref -> ref.event().heapAfterMb() != null)
        .toList();
    List<EventRef> returned = withHeap.stream().limit(limit).toList();
    List<Map<String, Object>> rows = returned.stream().map(ref -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("artifactId", ref.artifactId());
      row.put("timestamp", ref.event().timestamp().toString());
      row.put("eventId", ref.event().id());
      putIfPresent(row, "heapBeforeMb", ref.event().heapBeforeMb());
      putIfPresent(row, "heapAfterMb", ref.event().heapAfterMb());
      putIfPresent(row, "heapCommittedMb", ref.event().heapCommittedMb());
      putIfPresent(row, "heapReclaimedMb", ref.event().heapReclaimedMb());
      return row;
    }).toList();
    return ToolResult.success(
        "Found heap occupancy for " + withHeap.size() + " GC event(s); returning " + rows.size() + ".",
        Map.of("totalMatches", withHeap.size(), "samples", rows),
        evidence(returned),
        withHeap.size() > returned.size());
  }

  private static Map<String, Object> eventSummary(EventRef ref) {
    GcEvent event = ref.event();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("artifactId", ref.artifactId());
    row.put("eventId", event.id());
    row.put("timestamp", event.timestamp().toString());
    row.put("name", event.name());
    row.put("collector", event.collector().name());
    row.put("cause", event.cause());
    row.put("durationMillis", event.durationMillis());
    row.put("fullGc", event.fullGc());
    row.put("concurrent", event.concurrent());
    row.put("systemGc", event.systemGc());
    row.put("humongousAllocation", event.humongousAllocation());
    putIfPresent(row, "heapBeforeMb", event.heapBeforeMb());
    putIfPresent(row, "heapAfterMb", event.heapAfterMb());
    putIfPresent(row, "heapCommittedMb", event.heapCommittedMb());
    putIfPresent(row, "heapReclaimedMb", event.heapReclaimedMb());
    return row;
  }

  private static List<EventRef> events(InvestigationContext context) {
    List<EventRef> events = new ArrayList<>();
    for (ParsedArtifact parsed : context.parsedArtifacts()) {
      parsed.data().values().stream()
          .filter(GcLogData.class::isInstance)
          .map(GcLogData.class::cast)
          .flatMap(data -> data.events().stream())
          .map(event -> new EventRef(parsed.artifact().id(), event))
          .forEach(events::add);
    }
    events.sort(Comparator.comparing(ref -> ref.event().timestamp()));
    return events;
  }

  private static boolean matches(GcEvent event, Input input, Instant from, Instant until) {
    return (from == null || !event.timestamp().isBefore(from))
        && (until == null || !event.timestamp().isAfter(until))
        && contains(event.cause(), input.causeContains())
        && (input.collector().isBlank() || event.collector().name().equals(input.collector()))
        && ("ANY".equals(input.fullGc())
            || event.fullGc() == "TRUE".equals(input.fullGc()))
        && event.durationMillis() >= input.minimumDurationMillis();
  }

  private static List<EvidenceReference> evidence(List<EventRef> events) {
    return events.stream()
        .map(ref -> new EvidenceReference(
            ref.artifactId(),
            ref.event().timestamp(),
            "gc:" + ref.event().id()))
        .toList();
  }

  private static void putIfPresent(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
  }

  private static boolean contains(String value, String query) {
    return query.isBlank() || value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
  }

  private static Instant parseInstant(String value) {
    return value.isBlank() ? null : Instant.parse(value);
  }

  private static String normalize(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
