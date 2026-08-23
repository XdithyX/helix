package io.helix.investigations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.helix.agent.core.investigation.InvestigationContext;
import io.helix.core.model.Artifact;
import io.helix.core.model.ArtifactType;
import io.helix.core.model.CorrelationSummary;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.JvmFlavour;
import io.helix.core.model.JvmProfile;
import io.helix.core.model.ParsedArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWorkspaceInvestigationStoreTest {

  @TempDir
  Path workspaceDir;

  // ---- save and load round-trip ----

  @Test
  void saveAndFindReadyInvestigation() {
    LocalWorkspaceInvestigationStore store = new LocalWorkspaceInvestigationStore(workspaceDir);
    InvestigationId id = InvestigationId.generate();
    InvestigationContext context = minimalContext(id.value());

    store.saveReady(id, "test-inv", Instant.EPOCH, false, context);

    Optional<StoredInvestigation> found = store.find(id);
    assertThat(found).isPresent();
    assertThat(found.get().summary().id()).isEqualTo(id);
    assertThat(found.get().summary().name()).isEqualTo("test-inv");
    assertThat(found.get().summary().status()).isEqualTo(InvestigationStatus.READY);
    assertThat(found.get().context().report().artifacts()).hasSize(1);
    assertThat(found.get().context().parsedArtifacts()).hasSize(1);
  }

  @Test
  void findReturnsEmptyForUnknownId() {
    LocalWorkspaceInvestigationStore store = new LocalWorkspaceInvestigationStore(workspaceDir);
    assertThat(store.find(new InvestigationId("inv_unknownXXXXXXXXXXXXXXXXXX"))).isEmpty();
  }

  @Test
  void listReturnsAllSavedSummaries() {
    LocalWorkspaceInvestigationStore store = new LocalWorkspaceInvestigationStore(workspaceDir);
    InvestigationId id1 = InvestigationId.generate();
    InvestigationId id2 = InvestigationId.generate();

    store.saveReady(id1, "first", Instant.EPOCH, false, minimalContext(id1.value()));
    store.saveReady(id2, "second", Instant.EPOCH, true, minimalContext(id2.value()));

    List<InvestigationSummary> summaries = store.list();
    assertThat(summaries).hasSize(2);
    assertThat(summaries.stream().map(InvestigationSummary::name))
        .containsExactlyInAnyOrder("first", "second");
  }

  @Test
  void listReturnsEmptyWhenWorkspaceHasNoInvestigations() {
    LocalWorkspaceInvestigationStore store = new LocalWorkspaceInvestigationStore(workspaceDir);
    assertThat(store.list()).isEmpty();
  }

  // ---- FAILED record ----

  @Test
  void saveFailedPersistsManifestAndIsNotQueryableAsReady() {
    LocalWorkspaceInvestigationStore store = new LocalWorkspaceInvestigationStore(workspaceDir);
    InvestigationId id = InvestigationId.generate();
    InvestigationSummary failed = InvestigationSummary.failed(
        id, "broken", Instant.EPOCH, List.of(ArtifactType.THREAD_DUMP), false, "parse error");

    store.saveFailed(failed);

    // Not queryable via find (no evidence context)
    assertThat(store.find(id)).isEmpty();
    // But visible via list
    List<InvestigationSummary> list = store.list();
    assertThat(list).hasSize(1);
    assertThat(list.get(0).status()).isEqualTo(InvestigationStatus.FAILED);
    assertThat(list.get(0).failureReason()).isEqualTo("parse error");
  }

  // ---- atomic write: interrupted import is not queryable ----

  @Test
  void interruptedWriteIsNotQueryable() throws IOException {
    LocalWorkspaceInvestigationStore store = new LocalWorkspaceInvestigationStore(workspaceDir);
    InvestigationId id = new InvestigationId("inv_interruptedXXXXXXXXXX");

    // Simulate partial write: create only the temp dir, not the final dir
    Path tempDir = workspaceDir
        .resolve(LocalWorkspaceInvestigationStore.INVESTIGATIONS_DIR)
        .resolve(id.value() + "_tmp");
    Files.createDirectories(tempDir);
    // Write a manifest in the tmp dir but do NOT rename it
    Files.writeString(tempDir.resolve("manifest.json"), "{}");

    // The partial temp dir must not appear as a ready investigation
    assertThat(store.find(id)).isEmpty();
    // And not in the list (only completed dirs appear in list)
    assertThat(store.list()).isEmpty();
  }

  // ---- two different IDs return separate evidence ----

  @Test
  void twoInvestigationsReturnSeparateEvidence() {
    LocalWorkspaceInvestigationStore store = new LocalWorkspaceInvestigationStore(workspaceDir);
    InvestigationId id1 = InvestigationId.generate();
    InvestigationId id2 = InvestigationId.generate();

    InvestigationContext ctx1 = contextWithReportId(id1.value(), "report-1");
    InvestigationContext ctx2 = contextWithReportId(id2.value(), "report-2");

    store.saveReady(id1, "inv-1", Instant.EPOCH, false, ctx1);
    store.saveReady(id2, "inv-2", Instant.EPOCH, false, ctx2);

    StoredInvestigation found1 = store.find(id1).orElseThrow();
    StoredInvestigation found2 = store.find(id2).orElseThrow();

    assertThat(found1.context().report().reportId()).isEqualTo("report-1");
    assertThat(found2.context().report().reportId()).isEqualTo("report-2");
    // Confirm no cross-contamination
    assertThat(found1.context().report().reportId())
        .isNotEqualTo(found2.context().report().reportId());
  }

  // ---- workspace path confinement ----

  @Test
  void pathTraversalIdIsRejectedAtIdConstruction() {
    // InvestigationId rejects unsafe characters including '/' and '.'
    assertThatThrownBy(() -> new InvestigationId("../escape"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsafe characters");
    assertThatThrownBy(() -> new InvestigationId("../../etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void blankIdIsRejected() {
    assertThatThrownBy(() -> new InvestigationId(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InvestigationId("   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- helpers ----

  private static InvestigationContext minimalContext(String artifactId) {
    return contextWithReportId(artifactId, "report-" + artifactId);
  }

  private static InvestigationContext contextWithReportId(String artifactId, String reportId) {
    Artifact artifact = new Artifact(
        artifactId,
        ArtifactType.THREAD_DUMP,
        Instant.EPOCH,
        Map.of(),
        JvmFlavour.UNKNOWN,
        Path.of("/tmp/fake.txt"));
    ParsedArtifact parsed = new ParsedArtifact(artifact, List.of(), Map.of(), List.of());
    DiagnosticReport report = new DiagnosticReport(
        reportId,
        Instant.EPOCH,
        List.of(artifact),
        List.of(),
        List.of(),
        CorrelationSummary.empty(),
        JvmProfile.unknown(),
        null);
    return new InvestigationContext("", report, List.of(parsed));
  }
}
