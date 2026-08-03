package io.helix.agent.core.investigation.tools;

import io.helix.agent.core.investigation.DiagnosticTool;
import io.helix.agent.core.investigation.EvidenceReference;
import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.agent.core.investigation.ToolDefinition;
import io.helix.agent.core.investigation.ToolResult;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.jfr.JfrEvent;
import io.helix.core.model.jfr.JfrFrame;
import io.helix.core.model.jfr.JfrRecordingData;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class QueryJfrTool implements DiagnosticTool<QueryJfrTool.Input> {
  public record Input(
      String eventType,
      String from,
      String until,
      String threadContains,
      String classContains,
      String methodContains,
      String stackContains,
      String aggregation,
      int limit
  ) {
    public Input {
      eventType = clean(eventType);
      from = clean(from);
      until = clean(until);
      threadContains = clean(threadContains);
      classContains = clean(classContains);
      methodContains = clean(methodContains);
      stackContains = clean(stackContains);
      aggregation = normalize(aggregation, "EVENTS");
      limit = limit <= 0 ? 20 : Math.min(limit, 100);
    }
  }

  private record EventRef(String artifactId, JfrEvent event) {}

  private static final class Aggregate {
    private long count;
    private long bytes;
    private double durationMillis;

    void add(JfrEvent event) {
      count++;
      bytes += event.bytes();
      durationMillis += event.durationMillis();
    }

    Map<String, Object> asMap(String key) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("key", key);
      row.put("count", count);
      row.put("bytes", bytes);
      row.put("durationMillis", durationMillis);
      return row;
    }

    double score() {
      return bytes > 0 ? bytes : durationMillis > 0 ? durationMillis : count;
    }
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        "query_jfr",
        "Filter and aggregate normalized JFR events by time, type, thread, class, method, or stack.",
        ToolSchemas.object(Map.of(
            "eventType", ToolSchemas.string("Exact JFR event type, or empty for all event types."),
            "from", ToolSchemas.string("Inclusive ISO-8601 start time, or empty."),
            "until", ToolSchemas.string("Inclusive ISO-8601 end time, or empty."),
            "threadContains", ToolSchemas.string("Case-insensitive thread-name filter."),
            "classContains", ToolSchemas.string("Case-insensitive event class-name filter."),
            "methodContains", ToolSchemas.string("Case-insensitive event method-name filter."),
            "stackContains", ToolSchemas.string("Case-insensitive stack-frame filter."),
            "aggregation", ToolSchemas.enumeration(
                "Result shape.",
                "EVENTS", "COUNT", "TOP_EVENT_TYPES", "TOP_THREADS", "TOP_CLASSES",
                "TOP_METHODS", "TOP_STACKS"),
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
      return ToolResult.invalid("JFR time filters must be ISO-8601 instants: " + e.getParsedString());
    }
    if (from != null && until != null && from.isAfter(until)) {
      return ToolResult.invalid("'from' must not be after 'until'.");
    }

    List<EventRef> available = events(context);
    List<EventRef> matched = available.stream()
        .filter(ref -> matches(ref.event(), input, from, until))
        .toList();
    if (available.isEmpty()) {
      return ToolResult.unavailable(
          "No normalized JFR data is available.",
          "Supply a JFR recording containing supported events.");
    }

    return switch (input.aggregation()) {
      case "EVENTS" -> eventsResult(matched, input.limit());
      case "COUNT" -> countResult(matched);
      case "TOP_EVENT_TYPES" -> aggregateResult(matched, input.limit(), JfrEvent::eventType);
      case "TOP_THREADS" -> aggregateResult(matched, input.limit(), JfrEvent::threadName);
      case "TOP_CLASSES" -> aggregateResult(matched, input.limit(), JfrEvent::className);
      case "TOP_METHODS" -> aggregateResult(matched, input.limit(), QueryJfrTool::method);
      case "TOP_STACKS" -> aggregateResult(matched, input.limit(), QueryJfrTool::stack);
      default -> ToolResult.invalid("Unsupported JFR aggregation: " + input.aggregation());
    };
  }

  private static ToolResult eventsResult(List<EventRef> matched, int limit) {
    List<EventRef> returned = matched.stream().limit(limit).toList();
    return ToolResult.success(
        "Matched " + matched.size() + " JFR event(s); returning " + returned.size() + ".",
        Map.of("totalMatches", matched.size(), "events", returned.stream()
            .map(QueryJfrTool::eventSummary)
            .toList()),
        evidence(returned),
        matched.size() > returned.size());
  }

  private static ToolResult countResult(List<EventRef> matched) {
    return ToolResult.success(
        "Matched " + matched.size() + " JFR event(s).",
        Map.of("count", matched.size()),
        evidence(matched.stream().limit(20).toList()),
        matched.size() > 20);
  }

  private static ToolResult aggregateResult(
      List<EventRef> matched,
      int limit,
      java.util.function.Function<JfrEvent, String> keyFunction) {
    Map<String, Aggregate> aggregates = new LinkedHashMap<>();
    for (EventRef ref : matched) {
      String key = keyFunction.apply(ref.event());
      if (key != null && !key.isBlank()) {
        aggregates.computeIfAbsent(key, ignored -> new Aggregate()).add(ref.event());
      }
    }
    List<Map<String, Object>> groups = aggregates.entrySet().stream()
        .sorted(Comparator.<Map.Entry<String, Aggregate>>comparingDouble(
            entry -> entry.getValue().score()).reversed())
        .limit(limit)
        .map(entry -> entry.getValue().asMap(entry.getKey()))
        .toList();
    return ToolResult.success(
        "Aggregated " + matched.size() + " JFR event(s) into " + aggregates.size()
            + " group(s); returning " + groups.size() + ".",
        Map.of("totalMatches", matched.size(), "totalGroups", aggregates.size(), "groups", groups),
        evidence(matched.stream().limit(20).toList()),
        aggregates.size() > groups.size());
  }

  private static List<EventRef> events(InvestigationContext context) {
    List<EventRef> events = new ArrayList<>();
    for (ParsedArtifact parsed : context.parsedArtifacts()) {
      parsed.data().values().stream()
          .filter(JfrRecordingData.class::isInstance)
          .map(JfrRecordingData.class::cast)
          .flatMap(data -> data.events().stream())
          .map(event -> new EventRef(parsed.artifact().id(), event))
          .forEach(events::add);
    }
    events.sort(Comparator.comparing(ref -> ref.event().startTime()));
    return events;
  }

  private static boolean matches(
      JfrEvent event,
      Input input,
      Instant from,
      Instant until) {
    return (input.eventType().isBlank() || event.eventType().equals(input.eventType()))
        && (from == null || !event.startTime().isBefore(from))
        && (until == null || !event.startTime().isAfter(until))
        && contains(event.threadName(), input.threadContains())
        && contains(event.className(), input.classContains())
        && contains(event.methodName(), input.methodContains())
        && stackContains(event, input.stackContains());
  }

  private static Map<String, Object> eventSummary(EventRef ref) {
    JfrEvent event = ref.event();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("artifactId", ref.artifactId());
    row.put("eventType", event.eventType());
    row.put("startTime", event.startTime().toString());
    row.put("durationMillis", event.durationMillis());
    row.put("thread", event.threadName());
    row.put("class", event.className());
    row.put("method", event.methodName());
    row.put("bytes", event.bytes());
    row.put("lock", event.lockName());
    row.put("ioTarget", event.ioTarget());
    row.put("reason", event.reason());
    row.put("topFrame", event.topFrame());
    row.put("attributes", event.attributes());
    return row;
  }

  private static List<EvidenceReference> evidence(List<EventRef> events) {
    return events.stream()
        .map(ref -> new EvidenceReference(
            ref.artifactId(),
            ref.event().startTime(),
            "jfr:" + ref.event().eventType()))
        .toList();
  }

  private static boolean stackContains(JfrEvent event, String query) {
    if (query.isBlank()) {
      return true;
    }
    String lower = query.toLowerCase(Locale.ROOT);
    return event.stackTrace().stream()
        .map(JfrFrame::displayName)
        .anyMatch(frame -> frame.toLowerCase(Locale.ROOT).contains(lower));
  }

  private static String method(JfrEvent event) {
    if (!event.className().isBlank() && !event.methodName().isBlank()) {
      return event.className() + "." + event.methodName();
    }
    return event.methodName();
  }

  private static String stack(JfrEvent event) {
    return event.stackTrace().stream()
        .map(JfrFrame::displayName)
        .reduce((left, right) -> left + "\n" + right)
        .orElse("");
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
