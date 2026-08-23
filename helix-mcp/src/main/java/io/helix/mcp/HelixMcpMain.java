package io.helix.mcp;

import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.core.model.ArtifactType;
import io.modelcontextprotocol.server.McpSyncServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class HelixMcpMain {
  private static final String USAGE =
      "Usage: java -jar helix-mcp.jar "
          + "--workspace <path>\n"
          + "       java -jar helix-mcp.jar "
          + "[--type heap|thread|gclog|jfr|crash] [--correlate] "
          + "<artifact> [artifact ...]";
  private static volatile McpSyncServer server;
  private static volatile Thread analysisThread;

  private HelixMcpMain() {}

  public static void main(String[] args) {
    try {
      LaunchOptions options = parseArguments(args);
      if (options.workspacePath() != null) {
        // Workspace mode: no background analysis needed; evidence is pre-persisted.
        server = new WorkspaceMcpServer(options.workspacePath()).start();
      } else {
        // Static-artifact compatibility / demo mode.
        CompletableFuture<InvestigationContext> contextFuture = new CompletableFuture<>();
        server = new HelixMcpServer(contextFuture).start();
        analysisThread = Thread.ofVirtual().name("helix-mcp-analysis").start(() -> {
          try {
            contextFuture.complete(new HelixMcpAnalysis().analyze(
                options.artifactPaths(),
                options.artifactTypeOverride(),
                options.correlate()));
          } catch (Throwable failure) {
            contextFuture.completeExceptionally(failure);
            System.err.println("Helix MCP analysis failed: " + safeMessage(failure));
          }
        });
      }
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        McpSyncServer active = server;
        if (active != null) {
          active.close();
        }
      }, "helix-mcp-shutdown"));
    } catch (IllegalArgumentException e) {
      System.err.println("Helix MCP startup failed: " + safeMessage(e));
      System.err.println(USAGE);
      System.exit(2);
    } catch (RuntimeException e) {
      System.err.println("Helix MCP startup failed: " + safeMessage(e));
      System.exit(1);
    }
  }

  static LaunchOptions parseArguments(String[] args) {
    if (args == null || args.length == 0) {
      throw new IllegalArgumentException(
          "Specify --workspace <path> or at least one JVM artifact path.");
    }

    List<Path> artifactPaths = new ArrayList<>();
    ArtifactType artifactTypeOverride = null;
    boolean correlate = false;
    boolean optionsEnded = false;
    Path workspacePath = null;

    for (int index = 0; index < args.length; index++) {
      String argument = args[index];
      if (argument == null || argument.isBlank()) {
        throw new IllegalArgumentException("Artifact paths and option values must not be blank.");
      }
      if (!optionsEnded && argument.equals("--")) {
        optionsEnded = true;
      } else if (!optionsEnded && argument.equals("--workspace")) {
        if (workspacePath != null) {
          throw new IllegalArgumentException("--workspace may be specified only once.");
        }
        if (++index >= args.length) {
          throw new IllegalArgumentException("--workspace requires a value.");
        }
        workspacePath = Path.of(args[index]);
      } else if (!optionsEnded && argument.startsWith("--workspace=")) {
        if (workspacePath != null) {
          throw new IllegalArgumentException("--workspace may be specified only once.");
        }
        workspacePath = Path.of(argument.substring("--workspace=".length()));
      } else if (!optionsEnded && argument.equals("--correlate")) {
        if (correlate) {
          throw new IllegalArgumentException("--correlate may be specified only once.");
        }
        correlate = true;
      } else if (!optionsEnded && argument.equals("--type")) {
        if (artifactTypeOverride != null) {
          throw new IllegalArgumentException("--type may be specified only once.");
        }
        if (++index >= args.length) {
          throw new IllegalArgumentException("--type requires a value.");
        }
        artifactTypeOverride = HelixMcpAnalysis.resolveArtifactType(args[index]);
      } else if (!optionsEnded && argument.startsWith("--type=")) {
        if (artifactTypeOverride != null) {
          throw new IllegalArgumentException("--type may be specified only once.");
        }
        artifactTypeOverride = HelixMcpAnalysis.resolveArtifactType(
            argument.substring("--type=".length()));
      } else if (!optionsEnded && argument.startsWith("-")) {
        throw new IllegalArgumentException("Unknown option: " + argument);
      } else {
        artifactPaths.add(Path.of(argument));
      }
    }

    if (workspacePath != null) {
      if (!artifactPaths.isEmpty()) {
        throw new IllegalArgumentException(
            "--workspace and artifact paths are mutually exclusive.");
      }
      if (correlate || artifactTypeOverride != null) {
        throw new IllegalArgumentException(
            "--workspace is incompatible with --correlate and --type.");
      }
      Path resolved = workspacePath.toAbsolutePath().normalize();
      if (!Files.isDirectory(resolved)) {
        throw new IllegalArgumentException(
            "Workspace directory does not exist: " + resolved);
      }
      return new LaunchOptions(List.of(), null, false, resolved);
    }

    if (artifactPaths.isEmpty()) {
      throw new IllegalArgumentException(
          "Specify --workspace <path> or at least one JVM artifact path.");
    }
    return new LaunchOptions(List.copyOf(artifactPaths), artifactTypeOverride, correlate, null);
  }

  private static String safeMessage(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank()
        ? failure.getClass().getSimpleName()
        : message;
  }

  record LaunchOptions(
      List<Path> artifactPaths,
      ArtifactType artifactTypeOverride,
      boolean correlate,
      Path workspacePath
  ) {}
}
