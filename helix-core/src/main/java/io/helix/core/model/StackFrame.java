package io.helix.core.model;

public record StackFrame(
    String className,
    String methodName,
    String fileName,
    int lineNumber,
    String moduleName,
    boolean nativeFrame
) {
  public StackFrame {
    className = className == null ? "" : className;
    methodName = methodName == null ? "" : methodName;
    fileName = fileName == null ? "" : fileName;
    moduleName = moduleName == null ? "" : moduleName;
  }
}
