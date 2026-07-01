package io.helix.core.model.jfr;

public record JfrFrame(
    String className,
    String methodName,
    int lineNumber
) {
  public JfrFrame {
    className = className == null ? "" : className;
    methodName = methodName == null ? "" : methodName;
  }

  public String displayName() {
    String method = methodName.isBlank() ? "" : "." + methodName;
    String line = lineNumber >= 0 ? ":" + lineNumber : "";
    return className + method + line;
  }
}
