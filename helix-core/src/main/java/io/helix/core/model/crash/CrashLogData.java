package io.helix.core.model.crash;

import java.util.List;
import java.util.Map;

public record CrashLogData(
    String signal,
    String exceptionCode,
    ProblematicFrame problematicFrame,
    String currentThread,
    String jvmVersion,
    String jvmState,
    List<String> heapInfo,
    List<String> vmFlags,
    Map<String, String> environment,
    List<CrashStackFrame> stackFrames,
    List<String> compiledMethods,
    List<String> dynamicLibraries,
    Map<String, String> registers,
    boolean oomInHeader,
    Map<String, Object> metadata
) {
  public CrashLogData {
    signal = signal == null ? "" : signal;
    exceptionCode = exceptionCode == null ? "" : exceptionCode;
    problematicFrame = problematicFrame == null ? new ProblematicFrame("", "", "", "", "") : problematicFrame;
    currentThread = currentThread == null ? "" : currentThread;
    jvmVersion = jvmVersion == null ? "" : jvmVersion;
    jvmState = jvmState == null ? "" : jvmState;
    heapInfo = heapInfo == null ? List.of() : List.copyOf(heapInfo);
    vmFlags = vmFlags == null ? List.of() : List.copyOf(vmFlags);
    environment = environment == null ? Map.of() : Map.copyOf(environment);
    stackFrames = stackFrames == null ? List.of() : List.copyOf(stackFrames);
    compiledMethods = compiledMethods == null ? List.of() : List.copyOf(compiledMethods);
    dynamicLibraries = dynamicLibraries == null ? List.of() : List.copyOf(dynamicLibraries);
    registers = registers == null ? Map.of() : Map.copyOf(registers);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
