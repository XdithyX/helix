package io.helix.agent.core.investigation.tools;

import io.helix.agent.core.investigation.DiagnosticTool;
import io.helix.agent.core.investigation.EvidenceReference;
import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.agent.core.investigation.ToolDefinition;
import io.helix.agent.core.investigation.ToolResult;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.StackFrame;
import io.helix.core.model.thread.JavaThreadState;
import io.helix.core.model.thread.ThreadDumpData;
import io.helix.core.model.thread.ThreadDumpSnapshot;
import io.helix.core.model.thread.ThreadLock;
import io.helix.core.model.thread.ThreadSample;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class QueryThreadsTool implements DiagnosticTool<QueryThreadsTool.Input> {
  public record Input(
      String operation,
      String state,
      String threadNameContains,
      String stackContains,
      String lockId,
      int limit
  ) {
    public Input {
      operation = normalize(operation, "FILTER");
      state = normalize(state, "");
      threadNameContains = clean(threadNameContains);
      stackContains = clean(stackContains);
      lockId = clean(lockId);
      limit = limit <= 0 ? 20 : Math.min(limit, 100);
    }
  }

  private record SnapshotRef(String artifactId, ThreadDumpSnapshot snapshot) {}

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        "query_threads",
        "Query normalized thread dumps. Supports filtered threads, state distribution, lock graph, and duplicate stack groups.",
        ToolSchemas.object(Map.of(
            "operation", ToolSchemas.enumeration(
                "Query operation.", "FILTER", "STATE_DISTRIBUTION", "LOCK_GRAPH", "STACK_GROUPS"),
            "state", ToolSchemas.enumeration(
                "Thread state filter or empty for all.", "", "NEW", "RUNNABLE", "BLOCKED", "WAITING",
                "TIMED_WAITING", "TERMINATED", "UNKNOWN"),
            "threadNameContains", ToolSchemas.string("Case-insensitive thread-name filter."),
            "stackContains", ToolSchemas.string("Case-insensitive class, method, or displayed-frame filter."),
            "lockId", ToolSchemas.string("Lock identity to match as waiting or held."),
            "limit", ToolSchemas.integer("Maximum rows to return.", 1, 100))));
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public ToolResult execute(Input input, InvestigationContext context) {
    List<SnapshotRef> snapshots = latestSnapshots(context);
    if (snapshots.isEmpty()) {
      return ToolResult.unavailable(
          "No normalized thread-dump data is available.",
          "Supply a thread dump or JFR thread dump.");
    }
    return switch (input.operation()) {
      case "FILTER" -> filter(input, snapshots);
      case "STATE_DISTRIBUTION" -> distributions(snapshots);
      case "LOCK_GRAPH" -> lockGraph(input, snapshots);
      case "STACK_GROUPS" -> stackGroups(input, snapshots);
      default -> ToolResult.invalid("Unsupported thread operation: " + input.operation());
    };
  }

  private static ToolResult filter(Input input, List<SnapshotRef> snapshots) {
    JavaThreadState state = parseState(input.state());
    List<Map<String, Object>> all = new ArrayList<>();
    List<EvidenceReference> evidence = new ArrayList<>();
    for (SnapshotRef ref : snapshots) {
      Map<String, String> owners = lockOwners(ref.snapshot());
      for (ThreadSample thread : ref.snapshot().threads()) {
        if ((state == null || thread.state() == state)
            && contains(thread.name(), input.threadNameContains())
            && stackContains(thread, input.stackContains())
            && usesLock(thread, input.lockId())) {
          all.add(threadSummary(ref, thread, owners));
          evidence.add(new EvidenceReference(
              ref.artifactId(),
              ref.snapshot().capturedAt(),
              "thread:" + thread.name()));
        }
      }
    }
    List<Map<String, Object>> returned = all.stream().limit(input.limit()).toList();
    return ToolResult.success(
        "Matched " + all.size() + " thread(s); returning " + returned.size() + ".",
        Map.of("totalMatches", all.size(), "threads", returned),
        evidence.stream().limit(input.limit()).toList(),
        all.size() > returned.size());
  }

  private static ToolResult distributions(List<SnapshotRef> snapshots) {
    List<Map<String, Object>> rows = new ArrayList<>();
    List<EvidenceReference> evidence = new ArrayList<>();
    for (SnapshotRef ref : snapshots) {
      Map<JavaThreadState, Long> counts = new EnumMap<>(JavaThreadState.class);
      ref.snapshot().threads().forEach(thread -> counts.merge(thread.state(), 1L, Long::sum));
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("artifactId", ref.artifactId());
      row.put("capturedAt", ref.snapshot().capturedAt().toString());
      row.put("totalThreads", ref.snapshot().threads().size());
      row.put("states", counts);
      rows.add(row);
      evidence.add(new EvidenceReference(ref.artifactId(), ref.snapshot().capturedAt(), "thread-state-distribution"));
    }
    return ToolResult.success(
        "Returned thread-state distributions for " + rows.size() + " latest snapshot(s).",
        Map.of("distributions", rows),
        evidence,
        false);
  }

  private static ToolResult lockGraph(Input input, List<SnapshotRef> snapshots) {
    List<Map<String, Object>> edges = new ArrayList<>();
    List<EvidenceReference> evidence = new ArrayList<>();
    for (SnapshotRef ref : snapshots) {
      Map<String, String> owners = lockOwners(ref.snapshot());
      for (ThreadSample thread : ref.snapshot().threads()) {
        ThreadLock waiting = thread.waitingLock();
        if (waiting == null || (!input.lockId().isBlank() && !waiting.identity().equals(input.lockId()))) {
          continue;
        }
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("artifactId", ref.artifactId());
        edge.put("capturedAt", ref.snapshot().capturedAt().toString());
        edge.put("waitingThread", thread.name());
        edge.put("state", thread.state().name());
        edge.put("lockId", waiting.identity());
        edge.put("lockClass", waiting.className());
        edge.put("ownerThread", owners.getOrDefault(waiting.identity(), waiting.ownerThreadName()));
        edge.put("topFrame", thread.stackTrace().isEmpty() ? "" : displayName(thread.stackTrace().getFirst()));
        edges.add(edge);
        evidence.add(new EvidenceReference(ref.artifactId(), ref.snapshot().capturedAt(), "thread:" + thread.name()));
      }
    }
    List<Map<String, Object>> returned = edges.stream().limit(input.limit()).toList();
    return ToolResult.success(
        "Found " + edges.size() + " lock-wait edge(s); returning " + returned.size() + ".",
        Map.of("edges", returned),
        evidence.stream().limit(input.limit()).toList(),
        edges.size() > returned.size());
  }

  private static ToolResult stackGroups(Input input, List<SnapshotRef> snapshots) {
    List<Map<String, Object>> groups = new ArrayList<>();
    List<EvidenceReference> evidence = new ArrayList<>();
    for (SnapshotRef ref : snapshots) {
      Map<String, List<ThreadSample>> byStack = new LinkedHashMap<>();
      for (ThreadSample thread : ref.snapshot().threads()) {
        if (!stackContains(thread, input.stackContains())) {
          continue;
        }
        String stack = thread.stackTrace().stream()
            .map(QueryThreadsTool::displayName)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
        if (!stack.isBlank()) {
          byStack.computeIfAbsent(stack, ignored -> new ArrayList<>()).add(thread);
        }
      }
      byStack.entrySet().stream()
          .filter(entry -> entry.getValue().size() > 1)
          .sorted(Map.Entry.<String, List<ThreadSample>>comparingByValue(
              Comparator.comparingInt(List::size)).reversed())
          .forEach(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("artifactId", ref.artifactId());
            row.put("capturedAt", ref.snapshot().capturedAt().toString());
            row.put("count", entry.getValue().size());
            row.put("threads", entry.getValue().stream().map(ThreadSample::name).toList());
            row.put("topFrame", displayName(entry.getValue().getFirst().stackTrace().getFirst()));
            row.put("stack", entry.getKey().lines().toList());
            groups.add(row);
            entry.getValue().forEach(thread -> evidence.add(new EvidenceReference(
                ref.artifactId(), ref.snapshot().capturedAt(), "thread:" + thread.name())));
          });
    }
    List<Map<String, Object>> returned = groups.stream().limit(input.limit()).toList();
    return ToolResult.success(
        "Found " + groups.size() + " duplicate stack group(s); returning " + returned.size() + ".",
        Map.of("groups", returned),
        evidence.stream().limit(input.limit()).toList(),
        groups.size() > returned.size());
  }

  private static List<SnapshotRef> latestSnapshots(InvestigationContext context) {
    List<SnapshotRef> result = new ArrayList<>();
    for (ParsedArtifact parsed : context.parsedArtifacts()) {
      parsed.data().values().stream()
          .filter(ThreadDumpData.class::isInstance)
          .map(ThreadDumpData.class::cast)
          .filter(data -> !data.snapshots().isEmpty())
          .map(data -> data.snapshots().getLast())
          .forEach(snapshot -> result.add(new SnapshotRef(parsed.artifact().id(), snapshot)));
    }
    return result;
  }

  private static Map<String, Object> threadSummary(
      SnapshotRef ref,
      ThreadSample thread,
      Map<String, String> owners) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("artifactId", ref.artifactId());
    row.put("capturedAt", ref.snapshot().capturedAt().toString());
    row.put("name", thread.name());
    row.put("state", thread.state().name());
    row.put("cpuTimeMillis", thread.cpuTimeMillis());
    row.put("topFrame", thread.stackTrace().isEmpty() ? "" : displayName(thread.stackTrace().getFirst()));
    row.put("stack", thread.stackTrace().stream().map(QueryThreadsTool::displayName).toList());
    row.put("heldLocks", thread.heldLocks().stream().map(ThreadLock::displayName).toList());
    ThreadLock waiting = thread.waitingLock();
    if (waiting != null) {
      row.put("waitingLock", waiting.displayName());
      row.put("lockOwner", owners.getOrDefault(waiting.identity(), waiting.ownerThreadName()));
    }
    return row;
  }

  private static Map<String, String> lockOwners(ThreadDumpSnapshot snapshot) {
    Map<String, String> owners = new LinkedHashMap<>();
    for (ThreadSample thread : snapshot.threads()) {
      for (ThreadLock lock : thread.heldLocks()) {
        owners.putIfAbsent(lock.identity(), thread.name());
      }
    }
    return owners;
  }

  private static boolean usesLock(ThreadSample thread, String lockId) {
    if (lockId == null || lockId.isBlank()) {
      return true;
    }
    ThreadLock waiting = thread.waitingLock();
    return (waiting != null && waiting.identity().equals(lockId))
        || thread.heldLocks().stream().anyMatch(lock -> lock.identity().equals(lockId));
  }

  private static boolean stackContains(ThreadSample thread, String query) {
    if (query == null || query.isBlank()) {
      return true;
    }
    String lower = query.toLowerCase(Locale.ROOT);
    return thread.stackTrace().stream()
        .map(QueryThreadsTool::displayName)
        .anyMatch(frame -> frame.toLowerCase(Locale.ROOT).contains(lower));
  }

  private static boolean contains(String value, String query) {
    return query == null || query.isBlank()
        || value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
  }

  private static JavaThreadState parseState(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return JavaThreadState.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unsupported thread state: " + value);
    }
  }

  private static String normalize(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }

  private static String displayName(StackFrame frame) {
    String location = frame.fileName().isBlank()
        ? ""
        : ":" + frame.fileName() + (frame.lineNumber() > 0 ? ":" + frame.lineNumber() : "");
    return frame.className() + "." + frame.methodName() + location;
  }
}
