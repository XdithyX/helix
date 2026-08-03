package io.helix.parser.jfr;

import static org.assertj.core.api.Assertions.assertThat;

import io.helix.core.model.ArtifactType;
import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.TimelineEventType;
import io.helix.core.model.jfr.JfrRecordingData;
import io.helix.core.spi.ParseContext;
import java.nio.file.Path;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JfrArtifactParserTest {
  @TempDir
  Path tempDir;

  @Test
  void recognizesJfrFilesAndHints() {
    JfrArtifactParser parser = new JfrArtifactParser();

    assertThat(parser.canParse(Path.of("profile.jfr"), null)).isTrue();
    assertThat(parser.canParse(Path.of("profile.bin"), ArtifactType.JFR)).isTrue();
    assertThat(parser.canParse(Path.of("gc.log"), ArtifactType.GC_LOG)).isFalse();
  }

  @Test
  void parsesRequiredJfrEventTypesFromRecording() throws Exception {
    Path recording = tempDir.resolve("phase5.jfr");
    writeRecording(recording);

    ParsedArtifact parsed = new JfrArtifactParser().parse(recording, ParseContext.defaults());
    JfrRecordingData data = (JfrRecordingData) parsed.data().get(JfrArtifactParser.JFR_DATA_KEY);

    assertThat(data.events())
        .extracting("eventType")
        .contains(
            "jdk.ExecutionSample",
            "jdk.ObjectAllocationOutsideTLAB",
            "jdk.ObjectAllocationSample",
            "jdk.HeapDump",
            "jdk.OldObjectSample",
            "jdk.JavaMonitorEnter",
            "jdk.JavaMonitorWait",
            "jdk.FileRead",
            "jdk.Deoptimization",
            "jdk.VirtualThreadPinned");
    assertThat(data.events())
        .filteredOn(event -> event.eventType().equals("jdk.ObjectAllocationOutsideTLAB"))
        .anySatisfy(event -> {
          assertThat(event.bytes()).isEqualTo(64L * 1024L * 1024L);
          assertThat(event.className()).contains("byte");
        });
    assertThat(data.events())
        .filteredOn(event -> event.eventType().equals("jdk.ObjectAllocationSample"))
        .anySatisfy(event -> assertThat(event.bytes()).isEqualTo(32L * 1024L * 1024L));
    assertThat(data.events())
        .filteredOn(event -> event.eventType().equals("jdk.HeapDump"))
        .singleElement()
        .satisfies(event -> assertThat(event.attributes())
            .containsEntry("onOutOfMemoryError", true));
    assertThat(data.events())
        .filteredOn(event -> event.eventType().equals("jdk.OldObjectSample"))
        .singleElement()
        .satisfies(event -> {
          assertThat(event.bytes()).isEqualTo(10L * 1024L * 1024L);
          assertThat(event.className()).isEqualTo("byte[]");
        });
    assertThat(parsed.timelineEvents())
        .extracting("type")
        .contains(
            TimelineEventType.CPU_SPIKE,
            TimelineEventType.OOM,
            TimelineEventType.ALLOCATION_PRESSURE,
            TimelineEventType.THREAD_BLOCKED,
            TimelineEventType.BLOCKING_IO,
            TimelineEventType.DEOPT,
            TimelineEventType.VIRTUAL_THREAD_PINNED);
    assertThat(parsed.timelineEvents())
        .filteredOn(event -> event.type() == TimelineEventType.THREAD_BLOCKED)
        .allSatisfy(event -> assertThat(event.attributes())
            .containsEntry("jfr.event", "jdk.JavaMonitorEnter"));
    assertThat(parsed.timelineEvents())
        .filteredOn(event -> "jdk.JavaMonitorWait".equals(event.attributes().get("jfr.event")))
        .isEmpty();
  }

  private static void writeRecording(Path path) throws Exception {
    try (Recording recording = new Recording()) {
      recording.enable("jdk.ExecutionSample").withStackTrace();
      recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
      recording.enable("jdk.ObjectAllocationSample").withStackTrace();
      recording.enable("jdk.HeapDump").withStackTrace();
      recording.enable("jdk.OldObjectSample").withStackTrace();
      recording.enable("jdk.JavaMonitorEnter").withStackTrace();
      recording.enable("jdk.JavaMonitorWait").withStackTrace();
      recording.enable("jdk.FileRead").withStackTrace();
      recording.enable("jdk.Deoptimization").withStackTrace();
      recording.enable("jdk.VirtualThreadPinned").withStackTrace();
      recording.start();
      new ExecutionSampleEvent().commit();
      AllocationEvent allocation = new AllocationEvent();
      allocation.allocationSize = 64L * 1024L * 1024L;
      allocation.objectClass = "byte[]";
      allocation.commit();
      AllocationSampleEvent allocationSample = new AllocationSampleEvent();
      allocationSample.weight = 32L * 1024L * 1024L;
      allocationSample.objectClass = "java.lang.String";
      allocationSample.commit();
      HeapDumpEvent heapDump = new HeapDumpEvent();
      heapDump.onOutOfMemoryError = true;
      heapDump.destination = "heap_dump_fault.hprof";
      heapDump.commit();
      OldObjectSampleEvent oldObject = new OldObjectSampleEvent();
      oldObject.objectSize = 10L * 1024L * 1024L;
      oldObject.objectClass = "byte[]";
      oldObject.commit();
      MonitorEvent monitor = new MonitorEvent();
      monitor.monitorClass = "java.lang.Object";
      monitor.begin();
      Thread.sleep(5);
      monitor.commit();
      MonitorWaitEvent monitorWait = new MonitorWaitEvent();
      monitorWait.monitorClass = "java.lang.ref.ReferenceQueue$Lock";
      monitorWait.begin();
      Thread.sleep(5);
      monitorWait.commit();
      FileReadEvent fileRead = new FileReadEvent();
      fileRead.path = "/tmp/slow.data";
      fileRead.bytesRead = 4096;
      fileRead.begin();
      Thread.sleep(5);
      fileRead.commit();
      DeoptimizationEvent deopt = new DeoptimizationEvent();
      deopt.reason = "unstable_if";
      deopt.method = "com.example.Hot.method";
      deopt.commit();
      VirtualThreadPinnedEvent pinned = new VirtualThreadPinnedEvent();
      pinned.begin();
      Thread.sleep(5);
      pinned.commit();
      recording.stop();
      recording.dump(path);
    }
  }

  @Name("jdk.ExecutionSample")
  @StackTrace(true)
  static class ExecutionSampleEvent extends Event {}

  @Name("jdk.ObjectAllocationOutsideTLAB")
  @StackTrace(true)
  static class AllocationEvent extends Event {
    long allocationSize;
    String objectClass;
  }

  @Name("jdk.ObjectAllocationSample")
  @StackTrace(true)
  static class AllocationSampleEvent extends Event {
    long weight;
    String objectClass;
  }

  @Name("jdk.HeapDump")
  @StackTrace(true)
  static class HeapDumpEvent extends Event {
    boolean onOutOfMemoryError;
    String destination;
  }

  @Name("jdk.OldObjectSample")
  @StackTrace(true)
  static class OldObjectSampleEvent extends Event {
    long objectSize;
    String objectClass;
  }

  @Name("jdk.JavaMonitorEnter")
  @StackTrace(true)
  static class MonitorEvent extends Event {
    String monitorClass;
  }

  @Name("jdk.JavaMonitorWait")
  @StackTrace(true)
  static class MonitorWaitEvent extends Event {
    String monitorClass;
  }

  @Name("jdk.FileRead")
  @StackTrace(true)
  static class FileReadEvent extends Event {
    String path;
    long bytesRead;
  }

  @Name("jdk.Deoptimization")
  @StackTrace(true)
  static class DeoptimizationEvent extends Event {
    String reason;
    String method;
  }

  @Name("jdk.VirtualThreadPinned")
  @StackTrace(true)
  static class VirtualThreadPinnedEvent extends Event {}
}
