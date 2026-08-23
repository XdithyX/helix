package io.helix.investigations;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.ParsedArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * File-system backed {@link InvestigationStore} using an atomic temp-directory
 * rename strategy.
 *
 * <p>Layout under the workspace root:
 * <pre>
 * workspace/
 *   investigations/
 *     inv_abc.../
 *       manifest.json
 *       evidence.json    (List&lt;ParsedArtifact&gt; — normalized data, no raw bytes)
 *       report.json      (DiagnosticReport)
 * </pre>
 *
 * <p>A partial write lives under a temporary {@code inv_abc..._tmp} sibling and
 * is renamed atomically. The named directory is never queryable until the rename
 * succeeds. Concurrent reads of different IDs are safe because each read touches
 * only its own directory.
 */
public final class LocalWorkspaceInvestigationStore implements InvestigationStore {

  static final String INVESTIGATIONS_DIR = "investigations";
  static final String MANIFEST_FILE = "manifest.json";
  static final String EVIDENCE_FILE = "evidence.json";
  static final String REPORT_FILE = "report.json";

  private final Path workspaceRoot;
  private final ObjectMapper mapper;

  public LocalWorkspaceInvestigationStore(Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    this.mapper = InvestigationMapper.create();
  }

  @Override
  public InvestigationSummary save(StoredInvestigation investigation) {
    InvestigationId id = investigation.summary().id();
    Path targetDir = investigationDir(id);
    Path tempDir = tempDir(id);

    try {
      Files.createDirectories(investigationsRoot());
      deleteDirIfExists(tempDir);
      Files.createDirectory(tempDir);

      writeJson(tempDir.resolve(EVIDENCE_FILE), investigation.context().parsedArtifacts());
      writeJson(tempDir.resolve(REPORT_FILE), investigation.context().report());
      writeJson(tempDir.resolve(MANIFEST_FILE), investigation.summary());

      atomicRename(tempDir, targetDir);
    } catch (IOException e) {
      silentDeleteDir(tempDir);
      throw new InvestigationStoreException("Failed to save investigation " + id, e);
    }
    return investigation.summary();
  }

  public InvestigationSummary saveFailed(InvestigationSummary failedSummary) {
    if (failedSummary.status() != InvestigationStatus.FAILED) {
      throw new IllegalArgumentException("saveFailed requires FAILED status");
    }
    InvestigationId id = failedSummary.id();
    Path targetDir = investigationDir(id);
    Path tempDir = tempDir(id);

    try {
      Files.createDirectories(investigationsRoot());
      deleteDirIfExists(tempDir);
      Files.createDirectory(tempDir);
      writeJson(tempDir.resolve(MANIFEST_FILE), failedSummary);
      atomicRename(tempDir, targetDir);
    } catch (IOException e) {
      silentDeleteDir(tempDir);
      throw new InvestigationStoreException("Failed to save failed investigation " + id, e);
    }
    return failedSummary;
  }

  @Override
  public Optional<StoredInvestigation> find(InvestigationId id) {
    Path dir = safeInvestigationDir(id);
    if (dir == null || !Files.isDirectory(dir)) {
      return Optional.empty();
    }
    try {
      InvestigationSummary summary = readJson(dir.resolve(MANIFEST_FILE), InvestigationSummary.class);
      if (summary.status() != InvestigationStatus.READY) {
        return Optional.empty();
      }
      List<ParsedArtifact> parsedArtifacts = readParsedArtifacts(dir.resolve(EVIDENCE_FILE));
      DiagnosticReport report = readJson(dir.resolve(REPORT_FILE), DiagnosticReport.class);
      InvestigationContext context = new InvestigationContext("", report, parsedArtifacts);
      return Optional.of(new StoredInvestigation(summary, context));
    } catch (IOException e) {
      throw new InvestigationStoreException("Failed to load investigation " + id, e);
    }
  }

  @Override
  public List<InvestigationSummary> list() {
    Path root = investigationsRoot();
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    List<InvestigationSummary> summaries = new ArrayList<>();
    try (var entries = Files.list(root)) {
      entries
          .filter(Files::isDirectory)
          .filter(dir -> !dir.getFileName().toString().endsWith("_tmp"))
          .sorted()
          .forEach(dir -> {
            Path manifestFile = dir.resolve(MANIFEST_FILE);
            if (Files.isRegularFile(manifestFile)) {
              try {
                summaries.add(readJson(manifestFile, InvestigationSummary.class));
              } catch (IOException ignored) {
                // Unreadable manifest — skip this entry.
              }
            }
          });
    } catch (IOException e) {
      throw new InvestigationStoreException("Failed to list investigations", e);
    }
    return List.copyOf(summaries);
  }

  // ---- internal helpers ----

  private Path investigationsRoot() {
    return workspaceRoot.resolve(INVESTIGATIONS_DIR);
  }

  private Path investigationDir(InvestigationId id) {
    return investigationsRoot().resolve(id.value());
  }

  /**
   * Validates that the resolved path is inside the workspace. Returns {@code null}
   * if the ID would escape the workspace root (path confinement).
   */
  private Path safeInvestigationDir(InvestigationId id) {
    Path candidate = investigationsRoot().resolve(id.value()).toAbsolutePath().normalize();
    if (!candidate.startsWith(investigationsRoot().toAbsolutePath().normalize())) {
      return null;
    }
    return candidate;
  }

  private Path tempDir(InvestigationId id) {
    return investigationsRoot().resolve(id.value() + "_tmp");
  }

  private void writeJson(Path path, Object value) throws IOException {
    try (OutputStream out = Files.newOutputStream(path,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
      mapper.writeValue(out, value);
    }
  }

  private <T> T readJson(Path path, Class<T> type) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      return mapper.readValue(in, type);
    }
  }

  @SuppressWarnings("unchecked")
  private List<ParsedArtifact> readParsedArtifacts(Path path) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      return mapper.readValue(in,
          mapper.getTypeFactory().constructCollectionType(List.class, ParsedArtifact.class));
    }
  }

  private static void atomicRename(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void deleteDirIfExists(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      var sorted = paths.sorted(java.util.Comparator.reverseOrder()).toList();
      for (Path p : sorted) {
        Files.deleteIfExists(p);
      }
    }
  }

  private static void silentDeleteDir(Path dir) {
    try {
      deleteDirIfExists(dir);
    } catch (IOException ignored) {
      // best-effort cleanup; don't mask the primary exception
    }
  }

  /** Extracts {@link ArtifactType}s from a loaded context for summary construction. */
  static List<ArtifactType> artifactTypes(InvestigationContext context) {
    return context.parsedArtifacts().stream()
        .map(pa -> pa.artifact().type())
        .distinct()
        .sorted(java.util.Comparator.comparing(Enum::name))
        .toList();
  }

  /** Convenience: build and save a READY investigation from a context. */
  public InvestigationSummary saveReady(
      InvestigationId id,
      String name,
      Instant createdAt,
      boolean correlate,
      InvestigationContext context) {
    List<ArtifactType> types = artifactTypes(context);
    InvestigationSummary summary = InvestigationSummary.ready(id, name, createdAt, types, correlate);
    return save(new StoredInvestigation(summary, context));
  }
}
