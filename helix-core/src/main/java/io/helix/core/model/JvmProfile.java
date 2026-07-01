package io.helix.core.model;

import java.util.List;
import java.util.Map;

public record JvmProfile(
    JvmFlavour flavour,
    String javaVersion,
    String vendor,
    List<String> arguments,
    Map<String, Object> heapConfiguration,
    Map<String, Object> flags
) {
  public JvmProfile {
    flavour = flavour == null ? JvmFlavour.UNKNOWN : flavour;
    javaVersion = javaVersion == null ? "" : javaVersion;
    vendor = vendor == null ? "" : vendor;
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
    heapConfiguration = heapConfiguration == null ? Map.of() : Map.copyOf(heapConfiguration);
    flags = flags == null ? Map.of() : Map.copyOf(flags);
  }

  public static JvmProfile unknown() {
    return new JvmProfile(JvmFlavour.UNKNOWN, "", "", List.of(), Map.of(), Map.of());
  }
}
