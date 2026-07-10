package io.helix.analyzer.threads;

import io.helix.core.model.ArtifactType;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.Remediation;
import io.helix.core.model.RemediationType;
import io.helix.core.model.StackFrame;
import io.helix.core.model.thread.JavaThreadState;
import io.helix.core.model.thread.ThreadDumpData;
import io.helix.core.model.thread.ThreadDumpSnapshot;
import io.helix.core.model.thread.ThreadLock;
import io.helix.core.model.thread.ThreadSample;
import io.helix.core.spi.AnalysisContext;
import io.helix.core.spi.Analyzer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ThreadAnalyzer implements Analyzer {
  static final String THREAD_DATA_KEY = "threadDump";
  private static final int MIN_DEDUP_FRAMES = 3;
  private static final int MIN_DEDUP_THREADS = 2;
  private static final int MIN_POOL_THREADS = 3;
  private static final double POOL_EXHAUSTION_THRESHOLD = 0.90;
  private static final long CPU_HOTSPOT_THRESHOLD_MILLIS = 100;
  private static final Pattern TOMCAT_EXECUTOR =
      Pattern.compile("(.+?-(?:nio|bio|apr)-\\d+)-exec-\\d+");
  private static final Pattern FORK_JOIN =
      Pattern.compile("(ForkJoinPool-[^-]+)-worker-\\d+");
  private static final Pattern EXECUTOR_POOL =
      Pattern.compile("(pool-\\d+)-thread-\\d+");

  @Override
  public List<Finding> analyze(ParsedArtifact artifact, AnalysisContext ctx) {
    Object data = artifact.data().get(THREAD_DATA_KEY);
    if (!(data instanceof ThreadDumpData threadDump) || threadDump.snapshots().isEmpty()) {
      return List.of();
    }

    List<Finding> findings = new ArrayList<>();
    ThreadDumpSnapshot latest = threadDump.snapshots().getLast();
    addDeadlockFindings(artifact, latest, findings);
    addContentionFinding(artifact, latest, findings);
    addStateDistributionFinding(artifact, latest, findings);
    addStackDeduplicationFinding(artifact, latest, findings);
    addCpuHotspotFinding(artifact, latest, findings);
    addThreadPoolExhaustionFindings(artifact, latest, findings);
    addProgressionFinding(artifact, threadDump, findings);
    return findings;
  }

  @Override
  public Set<ArtifactType> supportedTypes() {
    return Set.of(ArtifactType.THREAD_DUMP);
  }

  @Override
  public String analyzerId() {
    return "helix-analyzer-threads";
  }

  private static void addDeadlockFindings(
      ParsedArtifact artifact,
      ThreadDumpSnapshot snapshot,
      List<Finding> findings) {
    List<DeadlockCycle> cycles = deadlockCycles(snapshot);
    int index = 1;
    for (DeadlockCycle cycle : cycles) {
      findings.add(new Finding(
          artifact.artifact().id() + "-thread-deadlock-" + index++,
          FindingSeverity.CRITICAL,
          FindingCategory.THREADING,
          "Java thread deadlock detected",
          deadlockExplanation(cycle),
          0.95,
          List.of(artifact.artifact().id()),
          List.of(),
          List.of(new Remediation(
              "Break the lock acquisition cycle between the listed threads.",
              RemediationType.CODE_CHANGE,
              "Enforce a single lock acquisition order or replace nested synchronized blocks with timed tryLock paths.",
              "Each thread in the cycle owns a lock required by the next thread, so none can make progress.")),
          mapOf("cycle", cycle.edges())));
    }
  }

  private static void addContentionFinding(
      ParsedArtifact artifact,
      ThreadDumpSnapshot snapshot,
      List<Finding> findings) {
    List<LockContention> ranking = lockContention(snapshot);
    if (ranking.isEmpty()) {
      return;
    }
    LockContention top = ranking.getFirst();
    findings.add(new Finding(
        artifact.artifact().id() + "-thread-contention-ranking",
        top.waitingThreads().size() >= 3 ? FindingSeverity.MEDIUM : FindingSeverity.LOW,
        FindingCategory.THREADING,
        "Lock contention ranking",
        top.waitingThreads().size() + " thread(s) are waiting for " + top.lock().displayName()
            + ownerPhrase(top.ownerThreadName()) + ".",
        0.78,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Inspect the lock owner and reduce work done while holding this monitor.",
            RemediationType.CODE_CHANGE,
            "Move blocking calls out of synchronized regions or partition the lock by key.",
            "Multiple waiting threads on the same lock indicate serialization or a stuck owner.")),
        mapOf("locks", ranking)));
  }

  private static void addStateDistributionFinding(
      ParsedArtifact artifact,
      ThreadDumpSnapshot snapshot,
      List<Finding> findings) {
    if (snapshot.threads().isEmpty()) {
      return;
    }
    Map<JavaThreadState, Long> distribution = new EnumMap<>(JavaThreadState.class);
    for (ThreadSample thread : snapshot.threads()) {
      distribution.merge(thread.state(), 1L, Long::sum);
    }
    findings.add(new Finding(
        artifact.artifact().id() + "-thread-state-distribution",
        FindingSeverity.INFO,
        FindingCategory.THREADING,
        "Thread state distribution",
        "The latest snapshot contains " + snapshot.threads().size()
            + " threads. RUNNABLE=" + distribution.getOrDefault(JavaThreadState.RUNNABLE, 0L)
            + ", BLOCKED=" + distribution.getOrDefault(JavaThreadState.BLOCKED, 0L)
            + ", WAITING=" + distribution.getOrDefault(JavaThreadState.WAITING, 0L)
            + ", TIMED_WAITING=" + distribution.getOrDefault(JavaThreadState.TIMED_WAITING, 0L) + ".",
        0.72,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Use the state distribution as the starting point for deeper thread analysis.",
            RemediationType.CODE_CHANGE,
            "",
            "A high BLOCKED count points to lock contention; a high RUNNABLE count may point to CPU pressure.")),
        mapOf("states", distribution)));
  }

  private static void addStackDeduplicationFinding(
      ParsedArtifact artifact,
      ThreadDumpSnapshot snapshot,
      List<Finding> findings) {
    List<StackGroup> groups = stackGroups(snapshot);
    if (groups.isEmpty()) {
      return;
    }
    StackGroup largest = groups.getFirst();
    findings.add(new Finding(
        artifact.artifact().id() + "-thread-stack-deduplication",
        largest.threadNames().size() >= 10 ? FindingSeverity.MEDIUM : FindingSeverity.INFO,
        FindingCategory.THREADING,
        "Duplicate thread stacks",
        largest.threadNames().size() + " threads share the same stack beginning at "
            + largest.topFrame() + ".",
        0.80,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Review why many threads are executing or waiting in the same code path.",
            RemediationType.CODE_CHANGE,
            "For worker pools, verify queue depth, downstream latency, and whether blocked calls should be asynchronous.",
            "Stack deduplication highlights repeated behavior that is easy to miss in large dumps.")),
        mapOf("groups", groups)));
  }

  private static void addCpuHotspotFinding(
      ParsedArtifact artifact,
      ThreadDumpSnapshot snapshot,
      List<Finding> findings) {
    List<ThreadSample> hotThreads = snapshot.threads().stream()
        .filter(thread -> thread.cpuTimeMillis() > CPU_HOTSPOT_THRESHOLD_MILLIS)
        .sorted(Comparator.comparing(ThreadSample::cpuTimeMillis).reversed())
        .toList();
    if (hotThreads.isEmpty()) {
      return;
    }
    ThreadSample top = hotThreads.getFirst();
    findings.add(new Finding(
        artifact.artifact().id() + "-thread-cpu-hotspot",
        FindingSeverity.MEDIUM,
        FindingCategory.THREADING,
        "CPU hotspot thread",
        top.name() + " has accumulated " + top.cpuTimeMillis()
            + "ms CPU time in the dump header.",
        0.65,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Validate whether the hot stack is expected for this JVM role.",
            RemediationType.CODE_CHANGE,
            "Pair this thread dump with JFR or async-profiler to confirm sustained CPU consumption.",
            "Thread dump CPU counters are a useful hint but not a full profile.")),
        mapOf("threads", hotThreads.stream().map(ThreadAnalyzer::threadSummary).toList())));
  }

  private static void addThreadPoolExhaustionFindings(
      ParsedArtifact artifact,
      ThreadDumpSnapshot snapshot,
      List<Finding> findings) {
    Map<String, List<ThreadSample>> pools = new LinkedHashMap<>();
    for (ThreadSample thread : snapshot.threads()) {
      poolName(thread.name()).ifPresent(pool -> pools.computeIfAbsent(pool, ignored -> new ArrayList<>()).add(thread));
    }

    int index = 1;
    for (Map.Entry<String, List<ThreadSample>> entry : pools.entrySet()) {
      List<ThreadSample> threads = entry.getValue();
      if (threads.size() < MIN_POOL_THREADS) {
        continue;
      }
      long active = threads.stream().filter(thread -> thread.state().isActive()).count();
      double ratio = active / (double) threads.size();
      if (ratio < POOL_EXHAUSTION_THRESHOLD) {
        continue;
      }
      findings.add(new Finding(
          artifact.artifact().id() + "-thread-pool-exhaustion-" + index++,
          FindingSeverity.HIGH,
          FindingCategory.THREADING,
          "Thread pool exhaustion: " + entry.getKey(),
          active + " of " + threads.size() + " threads in " + entry.getKey()
              + " are RUNNABLE or BLOCKED.",
          0.82,
          List.of(artifact.artifact().id()),
          List.of(),
          List.of(new Remediation(
              "Investigate why this executor has no idle capacity.",
              RemediationType.CONFIG_CHANGE,
              "Reduce blocking work, increase the pool only after finding the bottleneck, or add backpressure.",
              "A saturated pool can turn downstream latency into application-wide stalls.")),
          mapOf(
              "pool", entry.getKey(),
              "activeThreads", active,
              "totalThreads", threads.size(),
              "threads", threads.stream().map(ThreadSample::name).toList())));
    }
  }

  private static void addProgressionFinding(
      ParsedArtifact artifact,
      ThreadDumpData threadDump,
      List<Finding> findings) {
    if (threadDump.snapshots().size() < 2) {
      return;
    }
    List<StuckThread> stuckThreads = stuckThreads(threadDump.snapshots());
    if (stuckThreads.isEmpty()) {
      return;
    }
    FindingSeverity severity = stuckThreads.stream()
        .anyMatch(thread -> thread.state() == JavaThreadState.BLOCKED || thread.state() == JavaThreadState.RUNNABLE)
        ? FindingSeverity.MEDIUM
        : FindingSeverity.LOW;
    findings.add(new Finding(
        artifact.artifact().id() + "-thread-progression",
        severity,
        FindingCategory.THREADING,
        "Threads stuck across snapshots",
        stuckThreads.size() + " thread(s) stayed in the same state and stack across "
            + threadDump.snapshots().size() + " snapshots.",
        0.80,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Compare the stuck stacks with external symptoms during the capture window.",
            RemediationType.CODE_CHANGE,
            "For BLOCKED threads, inspect the owner; for RUNNABLE threads, confirm whether CPU is actually progressing.",
            "A thread unchanged across repeated snapshots is more suspicious than a single instantaneous state.")),
        mapOf("stuckThreads", stuckThreads)));
  }

  private static List<DeadlockCycle> deadlockCycles(ThreadDumpSnapshot snapshot) {
    Map<String, ThreadSample> byName = byName(snapshot.threads());
    Map<String, String> lockOwners = lockOwners(snapshot.threads());
    Map<String, WaitEdge> edges = new LinkedHashMap<>();
    for (ThreadSample thread : snapshot.threads()) {
      ThreadLock waiting = thread.waitingLock();
      if (waiting == null) {
        continue;
      }
      String owner = waiting.ownerThreadName().isBlank()
          ? lockOwners.get(waiting.identity())
          : waiting.ownerThreadName();
      if (owner != null && !owner.isBlank() && !owner.equals(thread.name()) && byName.containsKey(owner)) {
        edges.put(thread.name(), new WaitEdge(thread.name(), owner, waiting));
      }
    }

    List<DeadlockCycle> cycles = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String start : edges.keySet()) {
      List<WaitEdge> path = new ArrayList<>();
      Set<String> visiting = new LinkedHashSet<>();
      String current = start;
      while (edges.containsKey(current) && visiting.add(current)) {
        WaitEdge edge = edges.get(current);
        path.add(edge);
        current = edge.ownerThreadName();
      }
      if (!current.equals(start)) {
        continue;
      }
      String signature = cycleSignature(path);
      if (seen.add(signature)) {
        cycles.add(new DeadlockCycle(path));
      }
    }
    return cycles;
  }

  private static List<LockContention> lockContention(ThreadDumpSnapshot snapshot) {
    Map<String, String> lockOwners = lockOwners(snapshot.threads());
    Map<String, List<String>> waiters = new LinkedHashMap<>();
    Map<String, ThreadLock> locks = new LinkedHashMap<>();
    for (ThreadSample thread : snapshot.threads()) {
      ThreadLock waiting = thread.waitingLock();
      if (waiting == null) {
        continue;
      }
      waiters.computeIfAbsent(waiting.identity(), ignored -> new ArrayList<>()).add(thread.name());
      locks.putIfAbsent(waiting.identity(), waiting);
    }
    return waiters.entrySet().stream()
        .map(entry -> {
          ThreadLock lock = locks.get(entry.getKey());
          String owner = lock.ownerThreadName().isBlank() ? lockOwners.getOrDefault(entry.getKey(), "") : lock.ownerThreadName();
          return new LockContention(lock, owner, entry.getValue());
        })
        .sorted(Comparator.comparing((LockContention contention) -> contention.waitingThreads().size()).reversed())
        .toList();
  }

  private static List<StackGroup> stackGroups(ThreadDumpSnapshot snapshot) {
    Map<String, List<ThreadSample>> groups = new LinkedHashMap<>();
    for (ThreadSample thread : snapshot.threads()) {
      if (thread.stackTrace().size() < MIN_DEDUP_FRAMES) {
        continue;
      }
      groups.computeIfAbsent(stackFingerprint(thread), ignored -> new ArrayList<>()).add(thread);
    }
    return groups.values().stream()
        .filter(group -> group.size() >= MIN_DEDUP_THREADS)
        .map(group -> new StackGroup(
            group.stream().map(ThreadSample::name).toList(),
            formatFrame(group.getFirst().stackTrace().getFirst()),
            group.getFirst().stackTrace().stream().limit(8).map(ThreadAnalyzer::formatFrame).toList()))
        .sorted(Comparator.comparing((StackGroup group) -> group.threadNames().size()).reversed())
        .toList();
  }

  private static List<StuckThread> stuckThreads(List<ThreadDumpSnapshot> snapshots) {
    Map<String, List<ThreadSample>> byThread = new LinkedHashMap<>();
    for (ThreadDumpSnapshot snapshot : snapshots) {
      Set<String> namesInSnapshot = new HashSet<>();
      for (ThreadSample thread : snapshot.threads()) {
        if (namesInSnapshot.add(thread.name())) {
          byThread.computeIfAbsent(thread.name(), ignored -> new ArrayList<>()).add(thread);
        }
      }
    }

    List<StuckThread> stuck = new ArrayList<>();
    for (Map.Entry<String, List<ThreadSample>> entry : byThread.entrySet()) {
      List<ThreadSample> samples = entry.getValue();
      if (samples.size() != snapshots.size()) {
        continue;
      }
      JavaThreadState state = samples.getFirst().state();
      String stack = stackFingerprint(samples.getFirst());
      boolean unchanged = samples.stream()
          .allMatch(sample -> sample.state() == state && stackFingerprint(sample).equals(stack));
      if (unchanged) {
        stuck.add(new StuckThread(
            entry.getKey(),
            state,
            snapshots.size(),
            samples.getFirst().stackTrace().stream().limit(5).map(ThreadAnalyzer::formatFrame).toList()));
      }
    }
    return stuck;
  }

  private static Map<String, ThreadSample> byName(List<ThreadSample> threads) {
    Map<String, ThreadSample> result = new LinkedHashMap<>();
    for (ThreadSample thread : threads) {
      result.putIfAbsent(thread.name(), thread);
    }
    return result;
  }

  private static Map<String, String> lockOwners(List<ThreadSample> threads) {
    Map<String, String> result = new HashMap<>();
    for (ThreadSample thread : threads) {
      for (ThreadLock held : thread.heldLocks()) {
        result.putIfAbsent(held.identity(), thread.name());
      }
    }
    return result;
  }

  private static Optional<String> poolName(String threadName) {
    if (threadName.startsWith("Executor task launch worker-")) {
      return Optional.of("Spark executor task launch workers");
    }
    if (threadName.startsWith("dispatcher-Executor-")) {
      return Optional.of("Spark RPC dispatchers");
    }
    if (threadName.startsWith("shuffle-server-")) {
      return Optional.of("Spark shuffle server");
    }
    Matcher tomcat = TOMCAT_EXECUTOR.matcher(threadName);
    if (tomcat.matches()) {
      return Optional.of(tomcat.group(1) + "-exec");
    }
    Matcher forkJoin = FORK_JOIN.matcher(threadName);
    if (forkJoin.matches()) {
      return Optional.of(forkJoin.group(1));
    }
    Matcher executor = EXECUTOR_POOL.matcher(threadName);
    if (executor.matches()) {
      return Optional.of(executor.group(1));
    }
    return Optional.empty();
  }

  private static String deadlockExplanation(DeadlockCycle cycle) {
    StringBuilder explanation = new StringBuilder("A lock-wait cycle was found: ");
    for (int i = 0; i < cycle.edges().size(); i++) {
      WaitEdge edge = cycle.edges().get(i);
      if (i > 0) {
        explanation.append(" ");
      }
      explanation.append(edge.waitingThreadName())
          .append(" waits for ")
          .append(edge.lock().displayName())
          .append(" held by ")
          .append(edge.ownerThreadName())
          .append(".");
    }
    return explanation.toString();
  }

  private static String ownerPhrase(String ownerThreadName) {
    return ownerThreadName == null || ownerThreadName.isBlank() ? "" : ", owned by " + ownerThreadName;
  }

  private static String cycleSignature(List<WaitEdge> path) {
    return path.stream()
        .map(edge -> edge.waitingThreadName() + "->" + edge.ownerThreadName())
        .sorted()
        .reduce((left, right) -> left + "|" + right)
        .orElse("");
  }

  private static String stackFingerprint(ThreadSample thread) {
    return thread.stackTrace().stream()
        .map(frame -> frame.className() + "#" + frame.methodName() + ":" + frame.lineNumber())
        .reduce((left, right) -> left + "\n" + right)
        .orElse("<empty>");
  }

  private static String formatFrame(StackFrame frame) {
    return frame.className() + "." + frame.methodName()
        + (frame.fileName().isBlank() ? "" : "(" + frame.fileName()
            + (frame.lineNumber() >= 0 ? ":" + frame.lineNumber() : "") + ")");
  }

  private static Map<String, Object> threadSummary(ThreadSample thread) {
    return mapOf(
        "name", thread.name(),
        "state", thread.state(),
        "cpuTimeMillis", thread.cpuTimeMillis(),
        "topFrame", thread.stackTrace().isEmpty() ? "" : formatFrame(thread.stackTrace().getFirst()));
  }

  private static Map<String, Object> mapOf(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      result.put((String) values[i], values[i + 1]);
    }
    return result;
  }

  public record WaitEdge(String waitingThreadName, String ownerThreadName, ThreadLock lock) {}

  public record DeadlockCycle(List<WaitEdge> edges) {
    public DeadlockCycle {
      edges = edges == null ? List.of() : List.copyOf(edges);
    }
  }

  public record LockContention(ThreadLock lock, String ownerThreadName, List<String> waitingThreads) {
    public LockContention {
      ownerThreadName = ownerThreadName == null ? "" : ownerThreadName;
      waitingThreads = waitingThreads == null ? List.of() : List.copyOf(waitingThreads);
    }
  }

  public record StackGroup(List<String> threadNames, String topFrame, List<String> stackTrace) {
    public StackGroup {
      threadNames = threadNames == null ? List.of() : List.copyOf(threadNames);
      topFrame = topFrame == null ? "" : topFrame;
      stackTrace = stackTrace == null ? List.of() : List.copyOf(stackTrace);
    }
  }

  public record StuckThread(
      String threadName,
      JavaThreadState state,
      int snapshotCount,
      List<String> stackTrace) {
    public StuckThread {
      threadName = threadName == null ? "" : threadName;
      state = state == null ? JavaThreadState.UNKNOWN : state;
      stackTrace = stackTrace == null ? List.of() : List.copyOf(stackTrace);
    }
  }
}
