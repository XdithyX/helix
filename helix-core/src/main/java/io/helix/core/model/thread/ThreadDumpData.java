package io.helix.core.model.thread;

import java.util.List;
import java.util.Map;

public record ThreadDumpData(
    List<ThreadDumpSnapshot> snapshots,
    ThreadDumpFormat format,
    Map<String, Object> metadata
) {
  public ThreadDumpData {
    snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
    format = format == null ? inferFormat(snapshots) : format;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  public List<ThreadSample> allThreads() {
    return snapshots.stream()
        .flatMap(snapshot -> snapshot.threads().stream())
        .toList();
  }

  private static ThreadDumpFormat inferFormat(List<ThreadDumpSnapshot> snapshots) {
    if (snapshots == null || snapshots.isEmpty()) {
      return ThreadDumpFormat.UNKNOWN;
    }
    ThreadDumpFormat first = snapshots.getFirst().format();
    boolean same = snapshots.stream().allMatch(snapshot -> snapshot.format() == first);
    return same ? first : ThreadDumpFormat.MIXED;
  }
}
