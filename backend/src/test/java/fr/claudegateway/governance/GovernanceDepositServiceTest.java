package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.governance.dto.GovernanceDepositAction;
import fr.claudegateway.governance.dto.GovernanceDepositPlan;

/**
 * F-51 / SF-51-03 — l'annonce, puis le dépôt.
 *
 * <p>Deux promesses sont vérifiées ici et nulle part ailleurs : <b>on annonce avant d'écrire</b>, et
 * <b>on n'écrase jamais</b>. La seconde est la seule qui protège le travail de l'utilisateur.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceDepositServiceTest {

    @Mock
    private GovernanceActivationRepository activations;
    @Mock
    private GovernancePackageService packageService;
    @Mock
    private GovernanceProjectFiles projectFiles;
    @Mock
    private WorkspaceService workspaceService;

    private GovernanceDepositService service;

    private final UUID alice = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private Workspace workspace;
    private GovernancePackage pkg;
    private GovernanceActivation activation;

    @BeforeEach
    void setUp() {
        service = new GovernanceDepositService(activations, packageService, projectFiles,
                workspaceService);
        workspace = Workspace.builder().id(workspaceId).userId(alice).name("web").build();
        when(workspaceService.requireOwned(alice, workspaceId)).thenReturn(workspace);

        pkg = GovernancePackage.builder().id(UUID.randomUUID()).slug("livrables").name("Livrables")
                .version(4).published(true).rules("Aucune trace de LLM.").build();
        when(packageService.requirePublished(pkg.getId())).thenReturn(pkg);
        when(packageService.filesOf(pkg.getId())).thenReturn(List.of(
                file("STATE.md", GovernanceFileKind.TEMPLATE, "# État"),
                file(".claude/skills/explique.md", GovernanceFileKind.SKILL, "# explique")));

        activation = GovernanceActivation.builder().id(UUID.randomUUID()).userId(alice)
                .workspaceId(workspaceId).packageId(pkg.getId()).appliedVersion(3)
                .status(GovernanceActivationStatus.PENDING).build();
        when(activations.findByUserIdAndWorkspaceIdAndPackageId(alice, workspaceId, pkg.getId()))
                .thenReturn(Optional.of(activation));
        when(activations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(projectFiles.write(any(), any(), any(), any())).thenReturn(true);
    }

    private static GovernancePackageFile file(String path, GovernanceFileKind kind, String content) {
        return GovernancePackageFile.builder().id(UUID.randomUUID()).path(path).kind(kind)
                .content(content).build();
    }

    // ------------------------------------------------------------- l'annonce

    @Test
    @DisplayName("l'annonce dit ce qui sera créé et ce qui sera laissé tel quel")
    void planTellsCreateAndKeep() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of("STATE.md")));

        GovernanceDepositPlan plan = service.plan(alice, workspaceId, pkg.getId());

        assertThat(plan.readable()).isTrue();
        assertThat(plan.entries()).extracting("path", "action").containsExactly(
                org.assertj.core.groups.Tuple.tuple("STATE.md", GovernanceDepositAction.KEEP),
                org.assertj.core.groups.Tuple.tuple(".claude/skills/explique.md",
                        GovernanceDepositAction.CREATE));
        assertThat(plan.rules()).isTrue();
        // L'annonce n'écrit RIEN : c'est toute sa raison d'être.
        verify(projectFiles, never()).write(any(), any(), any(), any());
        verify(activations, never()).save(any());
    }

    @Test
    @DisplayName("un projet illisible n'autorise à conclure sur rien : tout est UNKNOWN")
    void planOnUnreadableProjectSaysUnknown() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.empty());

        GovernanceDepositPlan plan = service.plan(alice, workspaceId, pkg.getId());

        assertThat(plan.readable()).isFalse();
        assertThat(plan.entries()).allSatisfy(entry ->
                assertThat(entry.action()).isEqualTo(GovernanceDepositAction.UNKNOWN));
    }

    // --------------------------------------------------------------- le dépôt

    @Test
    @DisplayName("le dépôt crée ce qui manque et NE TOUCHE PAS à ce qui existe")
    void depositCreatesMissingAndNeverOverwrites() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of("STATE.md")));

        GovernanceDepositPlan done = service.deposit(alice, workspaceId, pkg.getId());

        verify(projectFiles).write(alice, workspace, ".claude/skills/explique.md", "# explique");
        // Le fichier déjà présent n'est jamais réécrit, même si son contenu diffère du paquet.
        verify(projectFiles, never()).write(eq(alice), eq(workspace), eq("STATE.md"), any());
        assertThat(done.entries()).extracting("action")
                .containsExactly(GovernanceDepositAction.KEEP, GovernanceDepositAction.CREATE);
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.APPLIED);
        assertThat(activation.getAppliedAt()).isNotNull();
        // Le dépôt réaligne la version appliquée sans rien réécrire.
        assertThat(activation.getAppliedVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("le dépôt rejoué n'écrit plus rien")
    void secondDepositWritesNothing() {
        when(projectFiles.listPaths(alice, workspace))
                .thenReturn(Optional.of(Set.of("STATE.md", ".claude/skills/explique.md")));

        GovernanceDepositPlan done = service.deposit(alice, workspaceId, pkg.getId());

        verify(projectFiles, never()).write(any(), any(), any(), any());
        assertThat(done.entries()).allSatisfy(entry ->
                assertThat(entry.action()).isEqualTo(GovernanceDepositAction.KEEP));
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.APPLIED);
    }

    @Test
    @DisplayName("une écriture refusée laisse l'activation en attente, les autres sont écrites")
    void partialFailureStaysPending() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of()));
        when(projectFiles.write(alice, workspace, "STATE.md", "# État")).thenReturn(false);

        GovernanceDepositPlan done = service.deposit(alice, workspaceId, pkg.getId());

        verify(projectFiles).write(alice, workspace, ".claude/skills/explique.md", "# explique");
        assertThat(done.entries()).extracting("action")
                .containsExactly(GovernanceDepositAction.UNKNOWN, GovernanceDepositAction.CREATE);
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.PENDING);
        assertThat(activation.getAppliedAt()).isNull();
    }

    @Test
    @DisplayName("une machine éteinte n'est pas une erreur : le paquet reste actif, en attente")
    void unreachableMachineStaysPending() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.empty());

        GovernanceDepositPlan done = service.deposit(alice, workspaceId, pkg.getId());

        assertThat(done.readable()).isFalse();
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.PENDING);
        verify(projectFiles, never()).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("un paquet sans fichier passe APPLIED tout de suite — il n'y a rien à attendre")
    void packageWithoutFilesIsAppliedImmediately() {
        when(packageService.filesOf(pkg.getId())).thenReturn(List.of());
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of()));

        GovernanceDepositPlan done = service.deposit(alice, workspaceId, pkg.getId());

        assertThat(done.entries()).isEmpty();
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.APPLIED);
    }

    @Test
    @DisplayName("appliquer un paquet qui n'est pas actif sur ce projet est introuvable")
    void applyOnInactivePackageIsNotFound() {
        when(activations.findByUserIdAndWorkspaceIdAndPackageId(alice, workspaceId, pkg.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deposit(alice, workspaceId, pkg.getId()))
                .isInstanceOf(GovernancePackageNotFoundException.class);
        verify(projectFiles, never()).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("un chemin devenu invalide n'est jamais écrit, et laisse l'activation en attente")
    void invalidStoredPathIsNeverWritten() {
        when(packageService.filesOf(pkg.getId()))
                .thenReturn(List.of(file("../voisin/STATE.md", GovernanceFileKind.TEMPLATE, "x")));
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of()));

        service.deposit(alice, workspaceId, pkg.getId());

        verify(projectFiles, never()).write(any(), any(), any(), any());
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.PENDING);
    }

    @Test
    @DisplayName("le dépôt silencieux ne lève jamais, même si le projet a disparu")
    void quietDepositNeverThrows() {
        when(workspaceService.requireOwned(alice, workspaceId))
                .thenThrow(new fr.claudegateway.atelier.WorkspaceNotFoundException("Projet introuvable."));

        service.depositAllQuietly(alice, workspaceId);

        verify(projectFiles, never()).write(any(), any(), any(), any());
    }
}
