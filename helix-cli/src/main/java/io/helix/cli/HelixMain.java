package io.helix.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "helix",
    description = "Unified Java diagnostic platform.",
    mixinStandardHelpOptions = true,
    subcommands = {
      AnalyzeCommand.class,
      InvestigationCommand.class,
      VersionCommand.class
    })
public final class HelixMain implements Callable<Integer> {
  @Spec
  CommandSpec spec;

  @Option(
      names = "--config",
      paramLabel = "<path>",
      defaultValue = "${user.home}/.helix/helix.yaml",
      description = "Path to helix.yaml.")
  Path configPath;

  @Option(
      names = "--output",
      paramLabel = "<format>",
      defaultValue = "text",
      description = "Output format for Phase 1: text|json.")
  String outputFormat;

  @Option(
      names = "--output-dir",
      paramLabel = "<path>",
      defaultValue = "./helix-reports/",
      description = "Output directory.")
  Path outputDir;

  @Option(names = {"-v", "--verbose"}, description = "Enable verbose logging.")
  boolean verbose;

  @Option(names = "--no-llm", description = "Disable LLM reasoning even if configured.")
  boolean noLlm;

  public static void main(String[] args) {
    int exitCode = new CommandLine(new HelixMain()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() {
    spec.commandLine().usage(spec.commandLine().getOut());
    return 0;
  }
}
