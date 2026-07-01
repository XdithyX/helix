package io.helix.core.model.jfr;

import java.util.List;

public record FlameGraphNode(
    String name,
    long samples,
    long bytes,
    long durationNanos,
    List<FlameGraphNode> children
) {
  public FlameGraphNode {
    name = name == null || name.isBlank() ? "<root>" : name;
    children = children == null ? List.of() : List.copyOf(children);
  }
}
