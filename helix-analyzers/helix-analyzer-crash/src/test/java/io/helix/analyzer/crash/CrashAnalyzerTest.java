package io.helix.analyzer.crash;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.Finding;
import io.helix.core.model.FindingSeverity;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.crash.CrashLogData;
import io.helix.core.model.crash.CrashStackFrame;
import io.helix.core.model.crash.ProblematicFrame;
import io.helix.core.spi.AnalysisContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrashAnalyzerTest {
  @Test
  void classifiesSigsegvApplicationJniAndStackCorruption() {
    List<Finding> findings = new CrashAnalyzer().analyze(parsedArtifact(sigsegvData()), AnalysisContext.defaults());

    assertThat(findings)
        .extracting(Finding::title)
        .contains(
            "JVM crash classification",
            "Application JNI library in crash frame",
            "Possible native stack corruption");
    assertThat(findings)
        .filteredOn(finding -> finding.title().equals("Application JNI library in crash frame"))
        .singleElement()
        .satisfies(finding -> assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH));
  }

  @Test
  void classifiesAbortAndOomCrashScenarios() {
    List<Finding> abortFindings = new CrashAnalyzer().analyze(parsedArtifact(abortData()), AnalysisContext.defaults());
    List<Finding> oomFindings = new CrashAnalyzer().analyze(parsedArtifact(oomData()), AnalysisContext.defaults());

    assertThat(abortFindings)
        .filteredOn(finding -> finding.title().equals("JVM crash classification"))
        .singleElement()
        .satisfies(finding -> assertThat(finding.explanation()).contains("SIGABRT"));
    assertThat(oomFindings)
        .filteredOn(finding -> finding.title().equals("JVM crash classification"))
        .singleElement()
        .satisfies(finding -> assertThat(finding.explanation()).contains("OutOfMemoryError"));
  }

  private static CrashLogData sigsegvData() {
    return new CrashLogData(
        "SIGSEGV",
        "0xb",
        new ProblematicFrame("C", "libnative_app.dylib", "Java_com_example_Native_crash", "0x12345", "raw"),
        "main",
        "OpenJDK 64-Bit Server VM (17.0.1+12)",
        "",
        List.of(),
        List.of("-Xmx2g"),
        Map.of("OS", "macOS x86_64", "CPU", "8 cores"),
        List.of(new CrashStackFrame("C", "libnative_app.dylib", "Java_com_example_Native_crash", "0x12345", "raw")),
        List.of(),
        List.of("/app/libnative_app.dylib"),
        Map.of("RBX", "0x00000000deadbeef"),
        false,
        Map.of());
  }

  private static CrashLogData abortData() {
    return new CrashLogData(
        "SIGABRT",
        "0x6",
        new ProblematicFrame("C", "libjvm.so", "report_vm_error", "0xabc", "raw"),
        "VM Thread",
        "OpenJDK 64-Bit Server VM (21.0.1+12)",
        "",
        List.of(),
        List.of(),
        Map.of("OS", "Linux x86_64", "CPU", "16 cores numa"),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        false,
        Map.of());
  }

  private static CrashLogData oomData() {
    return new CrashLogData(
        "SIGSEGV",
        "0xb",
        new ProblematicFrame("J", "", "java.util.Arrays.copyOf", "", "raw"),
        "http-executor-1",
        "OpenJDK 64-Bit Server VM (17.0.8+7)",
        "",
        List.of("used 4095M"),
        List.of("-Xmx4g"),
        Map.of("OS", "Linux x86_64", "CPU", "32 cores"),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        true,
        Map.of());
  }

  private static ParsedArtifact parsedArtifact(CrashLogData data) {
    Artifact artifact = new Artifact(
        "crash-sample",
        ArtifactType.CRASH_LOG,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of("filename", "hs_err_pid.log"),
        JvmFlavour.HOTSPOT,
        Path.of("/tmp/hs_err_pid.log"));
    return new ParsedArtifact(artifact, List.of(), Map.of(CrashAnalyzer.CRASH_DATA_KEY, data), List.of());
  }
}
