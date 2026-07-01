package io.helix.core.model.crash;

public record CrashStackFrame(
    String kind,
    String library,
    String symbol,
    String address,
    String raw
) {
  public CrashStackFrame {
    kind = kind == null ? "" : kind;
    library = library == null ? "" : library;
    symbol = symbol == null ? "" : symbol;
    address = address == null ? "" : address;
    raw = raw == null ? "" : raw;
  }
}
