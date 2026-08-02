package io.helix.analyzer.gc;

import io.helix.core.model.ArtifactType;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingCategory;
import io.helix.core.model.FindingReliability;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.Remediation;
import io.helix.core.model.RemediationType;
import io.helix.core.model.gc.GcEvent;
import io.helix.core.model.gc.GcLogData;
import io.helix.core.spi.AnalysisContext;
import io.helix.core.spi.Analyzer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class GcAnalyzer implements Analyzer {
  static final String GC_DATA_KEY = "gcLog";
  private static final double DEFAULT_PAUSE_WARNING_MS = 500.0;
  private static final double DEFAULT_THROUGHPUT_WARNING_PERCENT = 95.0;
  private static final int MIN_LEAK_GRADIENT_SAMPLES = 5;
  private static final Duration MIN_LEAK_GRADIENT_WINDOW = Duration.ofMinutes(5);
  private static final double HIGH_ALLOCATION_RATE_MB_PER_SEC = 512.0;
  private static final double HIGH_PROMOTION_RATE_MB_PER_SEC = 50.0;

  @Override
  public List<Finding> analyze(ParsedArtifact artifact, AnalysisContext ctx) {
    Object data = artifact.data().get(GC_DATA_KEY);
    if (!(data instanceof GcLogData gcLog) || gcLog.events().isEmpty()) {
      return List.of();
    }

    List<GcEvent> events = gcLog.events().stream()
        .sorted(Comparator.comparing(GcEvent::timestamp))
        .toList();
    List<Finding> findings = new ArrayList<>();
    double pauseWarningMs = doubleOption(ctx, "gc.pauseWarningMs", DEFAULT_PAUSE_WARNING_MS);
    double throughputWarningPercent = doubleOption(ctx, "gc.throughputWarningPercent", DEFAULT_THROUGHPUT_WARNING_PERCENT);

    addPauseStatisticsFindings(artifact, events, pauseWarningMs, findings);
    addThroughputFinding(artifact, events, throughputWarningPercent, findings);
    addCauseBreakdownFinding(artifact, events, findings);
    addSystemGcFinding(artifact, events, findings);
    addLeakGradientFinding(artifact, events, findings);
    addCausePatternFindings(artifact, events, findings);
    addAllocationRateFindings(artifact, events, findings);
    addPromotionRateFindings(artifact, events, findings);
    return findings;
  }

  @Override
  public Set<ArtifactType> supportedTypes() {
    return Set.of(ArtifactType.GC_LOG);
  }

  @Override
  public String analyzerId() {
    return "helix-analyzer-gc";
  }

  private static void addPauseStatisticsFindings(
      ParsedArtifact artifact,
      List<GcEvent> events,
      double pauseWarningMs,
      List<Finding> findings) {
    List<Double> pauses = pauseMillis(events);
    if (pauses.isEmpty()) {
      return;
    }
    PauseStats stats = PauseStats.from(pauses);
    findings.add(new Finding(
        artifact.artifact().id() + "-gc-pause-statistics",
        FindingSeverity.INFO,
        FindingCategory.GC,
        "GC pause statistics",
        "Observed " + pauses.size() + " GC pauses. p50=" + format(stats.p50Ms())
            + "ms, p95=" + format(stats.p95Ms()) + "ms, p99=" + format(stats.p99Ms())
            + "ms, max=" + format(stats.maxMs()) + "ms.",
        0.86,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Use the percentile distribution before changing collector settings.",
            RemediationType.JVM_FLAG,
            "",
            "A single max pause is less useful than the tail percentile trend.")),
        mapOf("pauseStats", stats)));

    if (stats.p99Ms() > pauseWarningMs) {
      findings.add(new Finding(
          artifact.artifact().id() + "-gc-p99-pause",
          stats.p99Ms() >= pauseWarningMs * 4.0 ? FindingSeverity.HIGH : FindingSeverity.MEDIUM,
          FindingCategory.GC,
          "GC p99 pause exceeds threshold",
          "p99 GC pause is " + format(stats.p99Ms()) + "ms, above the configured "
              + format(pauseWarningMs) + "ms threshold.",
          0.88,
          List.of(artifact.artifact().id()),
          List.of(),
          List.of(new Remediation(
              "Reduce GC tail latency before tuning for average throughput.",
              RemediationType.JVM_FLAG,
              "-XX:MaxGCPauseMillis=" + Math.round(pauseWarningMs),
              "Tail pauses are what users usually experience as stalls.")),
          mapOf("pauseStats", stats, "thresholdMs", pauseWarningMs)));
    }
  }

  private static void addThroughputFinding(
      ParsedArtifact artifact,
      List<GcEvent> events,
      double throughputWarningPercent,
      List<Finding> findings) {
    Throughput throughput = throughput(events);
    if (throughput == null) {
      return;
    }
    if (throughput.throughputPercent() < throughputWarningPercent) {
      findings.add(new Finding(
          artifact.artifact().id() + "-gc-throughput",
          throughput.throughputPercent() < 90.0 ? FindingSeverity.HIGH : FindingSeverity.MEDIUM,
          FindingCategory.GC,
          "GC throughput below target",
          "Application throughput is " + format(throughput.throughputPercent())
              + "% over the GC log window; total GC pause time is "
              + format(throughput.pauseMillis()) + "ms.",
          0.82,
          List.of(artifact.artifact().id()),
          List.of(),
          List.of(new Remediation(
              "Lower allocation pressure or revisit heap and collector sizing.",
              RemediationType.CONFIG_CHANGE,
              "Check allocation hotspots and old-gen growth before increasing heap size.",
              "Low GC throughput means the JVM is spending too much wall time in stop-the-world work.")),
          mapOf("throughput", throughput, "thresholdPercent", throughputWarningPercent)));
    }
  }

  private static void addCauseBreakdownFinding(
      ParsedArtifact artifact,
      List<GcEvent> events,
      List<Finding> findings) {
    Map<String, Long> causes = causeBreakdown(events);
    if (causes.isEmpty()) {
      return;
    }
    String topCause = causes.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
        .orElse("unknown");
    findings.add(new Finding(
        artifact.artifact().id() + "-gc-cause-breakdown",
        FindingSeverity.INFO,
        FindingCategory.GC,
        "GC cause breakdown",
        "Top GC cause is " + topCause + ".",
        0.78,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Use cause concentration to choose the next diagnostic artifact.",
            RemediationType.CODE_CHANGE,
            "",
            "Allocation failures, metadata thresholds, and explicit GCs point to very different fixes.")),
        mapOf("causes", causes)));
  }

  private static void addSystemGcFinding(
      ParsedArtifact artifact,
      List<GcEvent> events,
      List<Finding> findings) {
    List<GcEvent> systemGc = events.stream()
        .filter(event -> event.systemGc() || containsIgnoreCase(event.cause(), "System.gc"))
        .toList();
    if (systemGc.isEmpty()) {
      return;
    }
    findings.add(new Finding(
        artifact.artifact().id() + "-explicit-system-gc",
        FindingSeverity.HIGH,
        FindingCategory.GC,
        "Explicit System.gc() detected",
        systemGc.size() + " GC event(s) were triggered by System.gc(), which can force avoidable stop-the-world pauses.",
        0.92,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Find and remove explicit System.gc() callers unless this JVM has a deliberate maintenance window.",
            RemediationType.JVM_FLAG,
            "-XX:+DisableExplicitGC",
            "The spec requires explicit System.gc() calls to be treated as high severity because they bypass normal collector pacing.")),
        mapOf("events", systemGc.stream().map(GcAnalyzer::eventSummary).toList()))
        .withReliability(FindingReliability.FACT));
  }

  private static void addLeakGradientFinding(
      ParsedArtifact artifact,
      List<GcEvent> events,
      List<Finding> findings) {
    LeakGradient gradient = leakGradient(events);
    if (gradient == null || gradient.slopeMbPerHour() <= 0.0 || gradient.rSquared() < 0.50) {
      return;
    }
    findings.add(new Finding(
        artifact.artifact().id() + "-heap-after-gc-gradient",
        gradient.slopeMbPerHour() >= 512.0 ? FindingSeverity.HIGH : FindingSeverity.MEDIUM,
        FindingCategory.MEMORY,
        "Heap-after-GC leak gradient",
        "Heap after GC is trending upward at " + format(gradient.slopeMbPerHour())
            + " MB/hour (95% CI " + format(gradient.confidenceLowMbPerHour()) + " to "
            + format(gradient.confidenceHighMbPerHour()) + "), r2=" + format(gradient.rSquared()) + ".",
        Math.min(0.95, 0.55 + (0.35 * gradient.rSquared())),
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Capture a heap dump near the end of the rising trend and compare retained objects.",
            RemediationType.CODE_CHANGE,
            "Use `helix analyze --type heap` on a heap dump from the same JVM window.",
            "A rising post-GC floor means live data is accumulating between collections.")),
        mapOf("leakGradient", gradient))
        .withReliability(FindingReliability.HEURISTIC));
  }

  private static void addCausePatternFindings(
      ParsedArtifact artifact,
      List<GcEvent> events,
      List<Finding> findings) {
    long humongous = events.stream()
        .filter(event -> event.humongousAllocation() || containsIgnoreCase(event.cause(), "Humongous"))
        .count();
    if (humongous > 0) {
      findings.add(new Finding(
          artifact.artifact().id() + "-humongous-allocation-pattern",
          FindingSeverity.MEDIUM,
          FindingCategory.GC,
          "Humongous allocation GC pattern",
          humongous + " GC event(s) were caused by humongous allocation pressure.",
          0.80,
          List.of(artifact.artifact().id()),
          List.of(),
          List.of(new Remediation(
              "Inspect large object allocation sites and G1 region sizing.",
              RemediationType.JVM_FLAG,
              "Review -XX:G1HeapRegionSize only after confirming object size distribution.",
              "Humongous allocations can fragment G1 regions and trigger disruptive collection patterns.")),
          mapOf("count", humongous)));
    }

    long metadata = events.stream()
        .filter(event -> containsIgnoreCase(event.cause(), "Metadata"))
        .count();
    if (metadata >= 2 || (events.size() >= 5 && metadata >= Math.ceil(events.size() * 0.20))) {
      findings.add(new Finding(
          artifact.artifact().id() + "-metadata-gc-pattern",
          FindingSeverity.MEDIUM,
          FindingCategory.GC,
          "Frequent metadata-triggered GC",
          metadata + " GC event(s) were triggered by metadata pressure.",
          0.75,
          List.of(artifact.artifact().id()),
          List.of(),
          List.of(new Remediation(
              "Check class loading churn and metaspace sizing.",
              RemediationType.JVM_FLAG,
              "-XX:MaxMetaspaceSize should leave headroom for normal class loading.",
              "Frequent metadata threshold GCs often point to dynamic class generation or tight metaspace limits.")),
          mapOf("count", metadata)));
    }
  }

  private static void addAllocationRateFindings(
      ParsedArtifact artifact,
      List<GcEvent> events,
      List<Finding> findings) {
    RateSummary allocationRate = allocationRate(events);
    if (allocationRate == null) {
      return;
    }
    findings.add(new Finding(
        artifact.artifact().id() + "-allocation-rate",
        allocationRate.peakMbPerSecond() >= HIGH_ALLOCATION_RATE_MB_PER_SEC ? FindingSeverity.MEDIUM : FindingSeverity.INFO,
        FindingCategory.GC,
        allocationRate.peakMbPerSecond() >= HIGH_ALLOCATION_RATE_MB_PER_SEC
            ? "High allocation rate"
            : "Allocation rate summary",
        "Average allocation rate is " + format(allocationRate.averageMbPerSecond())
            + " MB/s; peak is " + format(allocationRate.peakMbPerSecond()) + " MB/s.",
        0.70,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Pair high allocation periods with allocation profiling.",
            RemediationType.CODE_CHANGE,
            "Use JFR allocation events or async-profiler alloc mode to find hot allocation sites.",
            "Allocation rate from GC logs is directional; object-level attribution comes from JFR or heap data.")),
        mapOf("allocationRate", allocationRate)));
  }

  private static void addPromotionRateFindings(
      ParsedArtifact artifact,
      List<GcEvent> events,
      List<Finding> findings) {
    RateSummary promotionRate = promotionRate(events);
    if (promotionRate == null) {
      return;
    }
    findings.add(new Finding(
        artifact.artifact().id() + "-promotion-rate",
        promotionRate.peakMbPerSecond() >= HIGH_PROMOTION_RATE_MB_PER_SEC ? FindingSeverity.MEDIUM : FindingSeverity.INFO,
        FindingCategory.GC,
        promotionRate.peakMbPerSecond() >= HIGH_PROMOTION_RATE_MB_PER_SEC
            ? "High promotion rate"
            : "Promotion rate summary",
        "Average promotion rate is " + format(promotionRate.averageMbPerSecond())
            + " MB/s; peak is " + format(promotionRate.peakMbPerSecond()) + " MB/s.",
        0.68,
        List.of(artifact.artifact().id()),
        List.of(),
        List.of(new Remediation(
            "Inspect long-lived allocation patterns if promotion remains high.",
            RemediationType.CODE_CHANGE,
            "Compare with a heap dump to identify objects surviving young collections.",
            "High promotion rate means young objects are aging into old generation instead of dying quickly.")),
        mapOf("promotionRate", promotionRate)));
  }

  private static List<Double> pauseMillis(List<GcEvent> events) {
    return events.stream()
        .filter(GcEvent::pause)
        .map(GcEvent::durationMillis)
        .sorted()
        .toList();
  }

  private static Throughput throughput(List<GcEvent> events) {
    if (events.size() < 2) {
      return null;
    }
    double windowMs = elapsedMillis(events.getFirst(), events.getLast());
    if (windowMs <= 0.0) {
      return null;
    }
    double pauseMs = pauseMillis(events).stream().mapToDouble(Double::doubleValue).sum();
    double throughput = Math.max(0.0, 100.0 * (1.0 - (pauseMs / windowMs)));
    return new Throughput(windowMs, pauseMs, throughput);
  }

  private static Map<String, Long> causeBreakdown(List<GcEvent> events) {
    return events.stream()
        .collect(Collectors.groupingBy(
            event -> event.cause().isBlank() ? "Unknown" : event.cause(),
            LinkedHashMap::new,
            Collectors.counting()));
  }

  private static LeakGradient leakGradient(List<GcEvent> events) {
    List<Sample> samples = events.stream()
        .filter(event -> event.heapAfterMb() != null)
        .map(event -> new Sample(event.timestamp(), event.uptime(), event.heapAfterMb()))
        .toList();
    if (samples.size() < MIN_LEAK_GRADIENT_SAMPLES) {
      return null;
    }

    Sample first = samples.getFirst();
    double windowMillis = elapsedMillis(first, samples.getLast());
    if (windowMillis < MIN_LEAK_GRADIENT_WINDOW.toMillis()) {
      return null;
    }

    double[] x = new double[samples.size()];
    double[] y = new double[samples.size()];
    for (int i = 0; i < samples.size(); i++) {
      x[i] = elapsedMillis(first, samples.get(i)) / 3_600_000.0;
      y[i] = samples.get(i).heapAfterMb();
    }

    double xMean = mean(x);
    double yMean = mean(y);
    double sxx = 0.0;
    double sxy = 0.0;
    double syy = 0.0;
    for (int i = 0; i < x.length; i++) {
      double dx = x[i] - xMean;
      double dy = y[i] - yMean;
      sxx += dx * dx;
      sxy += dx * dy;
      syy += dy * dy;
    }
    if (sxx == 0.0) {
      return null;
    }

    double slope = sxy / sxx;
    double intercept = yMean - slope * xMean;
    double sse = 0.0;
    for (int i = 0; i < x.length; i++) {
      double residual = y[i] - (intercept + slope * x[i]);
      sse += residual * residual;
    }
    double rSquared = syy == 0.0 ? 1.0 : Math.max(0.0, 1.0 - (sse / syy));
    double standardError = x.length > 2 ? Math.sqrt((sse / (x.length - 2)) / sxx) : 0.0;
    double margin = 1.96 * standardError;
    return new LeakGradient(
        samples.size(), windowMillis, slope, slope - margin, slope + margin, rSquared);
  }

  private static RateSummary allocationRate(List<GcEvent> events) {
    List<Double> rates = new ArrayList<>();
    GcEvent previous = null;
    for (GcEvent event : events) {
      if (event.edenBeforeMb() == null) {
        previous = event;
        continue;
      }
      if (previous != null) {
        double seconds = elapsedMillis(previous, event) / 1000.0;
        if (seconds > 0.0) {
          rates.add(event.edenBeforeMb() / seconds);
        }
      }
      previous = event;
    }
    return summarizeRates(rates);
  }

  private static RateSummary promotionRate(List<GcEvent> events) {
    List<Double> rates = new ArrayList<>();
    for (GcEvent event : events) {
      if (event.oldBeforeMb() == null || event.oldAfterMb() == null) {
        continue;
      }
      double promotedMb = Math.max(0.0, event.oldAfterMb() - event.oldBeforeMb());
      double seconds = event.duration().toNanos() / 1_000_000_000.0;
      if (seconds > 0.0 && promotedMb > 0.0) {
        rates.add(promotedMb / seconds);
      }
    }
    return summarizeRates(rates);
  }

  private static RateSummary summarizeRates(List<Double> rates) {
    if (rates.isEmpty()) {
      return null;
    }
    double average = rates.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    double peak = rates.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    return new RateSummary(rates.size(), average, peak);
  }

  private static double elapsedMillis(GcEvent first, GcEvent last) {
    double timestampMs = Duration.between(first.timestamp(), last.timestamp()).toMillis();
    if (timestampMs > 0.0) {
      return timestampMs;
    }
    return last.uptime().minus(first.uptime()).toMillis();
  }

  private static double elapsedMillis(Sample first, Sample last) {
    double timestampMs = Duration.between(first.timestamp(), last.timestamp()).toMillis();
    if (timestampMs > 0.0) {
      return timestampMs;
    }
    return last.uptime().minus(first.uptime()).toMillis();
  }

  private static double mean(double[] values) {
    double total = 0.0;
    for (double value : values) {
      total += value;
    }
    return total / values.length;
  }

  private static double doubleOption(AnalysisContext ctx, String name, double fallback) {
    Object value = ctx.options().get(name);
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Double.parseDouble(text);
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private static Map<String, Object> eventSummary(GcEvent event) {
    return mapOf(
        "id", event.id(),
        "timestamp", event.timestamp().toString(),
        "durationMs", event.durationMillis(),
        "cause", event.cause(),
        "heapBeforeMb", event.heapBeforeMb(),
        "heapAfterMb", event.heapAfterMb());
  }

  private static String format(double value) {
    return String.format(java.util.Locale.ROOT, "%.2f", value);
  }

  private static boolean containsIgnoreCase(String text, String pattern) {
    return text != null && text.toLowerCase(java.util.Locale.ROOT).contains(pattern.toLowerCase(java.util.Locale.ROOT));
  }

  private static Map<String, Object> mapOf(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      Object value = values[i + 1];
      if (value != null) {
        result.put((String) values[i], value);
      }
    }
    return result;
  }

  public record PauseStats(
      double minMs,
      double maxMs,
      double meanMs,
      double p50Ms,
      double p95Ms,
      double p99Ms,
      double p999Ms) {
    static PauseStats from(List<Double> sortedMillis) {
      double mean = sortedMillis.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
      return new PauseStats(
          sortedMillis.getFirst(),
          sortedMillis.getLast(),
          mean,
          percentile(sortedMillis, 50.0),
          percentile(sortedMillis, 95.0),
          percentile(sortedMillis, 99.0),
          percentile(sortedMillis, 99.9));
    }

    private static double percentile(List<Double> sorted, double percentile) {
      if (sorted.isEmpty()) {
        return 0.0;
      }
      int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
      return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
  }

  public record Throughput(double windowMillis, double pauseMillis, double throughputPercent) {}

  public record LeakGradient(
      int sampleCount,
      double windowMillis,
      double slopeMbPerHour,
      double confidenceLowMbPerHour,
      double confidenceHighMbPerHour,
      double rSquared) {}

  public record RateSummary(int sampleCount, double averageMbPerSecond, double peakMbPerSecond) {}

  private record Sample(Instant timestamp, Duration uptime, double heapAfterMb) {}
}
