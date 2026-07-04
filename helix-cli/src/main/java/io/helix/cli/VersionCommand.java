package io.helix.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "version", description = "Print version and component information.")
final class VersionCommand implements Callable<Integer> {
  @Spec
  CommandSpec spec;

  @Override
  public Integer call() {
    spec.commandLine().getOut().println("Helix " + BuildInfo.version());
    spec.commandLine().getOut().println("Java " + System.getProperty("java.version"));
    spec.commandLine().getOut().println("Components:");
    BuildInfo.components().forEach(component -> spec.commandLine().getOut().println("- " + component));
    return 0;
  }
}
