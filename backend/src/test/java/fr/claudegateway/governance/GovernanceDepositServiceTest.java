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
import fr.claudegateway.governance.dto.GovernanceDepositAction;
import fr.claudegateway.governance.dto.GovernanceDepositPlan;
import fr.claudegateway.governance.dto.GovernanceProjectDepositPlan;

/**
 * F-51 / SF-51-03, regrainé par F-75 / SF-75-01 — l'annonce, puis le dépôt, <b>sur tous les dossiers
 * d'un poste</b>.
 *
 * <p>Trois promesses sont vérifiées ici et nulle part ailleurs : <b>on annonce avant d'écrire</b>,
 * <b>on n'écrase jamais</b>, et <b>un poste gouverne tous ses dossiers</b>. La deuxième est la seule
 * qui protège le travail de l'utilisateur ; la troisième est la raison d'être de F-75.</p>
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
    private GovernanceHostScope hostScope;

    private GovernanceDepositService service;

    private final UUID alice = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(hostId);
    private final UUID workspaceId = UUID.randomUUID();
    private Workspace workspace;
    private GovernancePackage pkg;
    private GovernanceActivation activation;

    @BeforeEach
    void setUp() {
        service = new GovernanceDepositService(activations, packageService, projectFiles, hostScope);
        workspace = Workspace.builder().id(workspaceId).userId(alice).name("web").hostId(hostId)
                .build();
        when(hostScope.projectsOf(alice, host)).thenReturn(List.of(workspace));
        when(hostScope.projectOf(alice, workspaceId)).thenReturn(workspace);
        when(hostScope.hostOf(workspace)).thenReturn(host);
        when(hostScope.nameOf(alice, host)).thenReturn("EDENRED");

        pkg = GovernancePackage.builder().id(UUID.randomUUID()).slug("livrables").name("Livrables")
                .version(4).published(true).rules("Aucune trace de LLM.").build();
        when(packageService.requirePublished(pkg.getId())).thenReturn(pkg);
        when(packageService.filesOf(pkg.getId())).thenReturn(List.of(
                file("STATE.md", GovernanceFileKind.TEMPLATE, "# État"),
                file(".claude/skills/explique.md", GovernanceFileKind.SKILL, "# explique")));

        activation = GovernanceActivation.builder().id(UUID.randomUUID()).userId(alice)
                .hostId(hostId).packageId(pkg.getId()).appliedVersion(3)
                .status(GovernanceActivationStatus.PENDING).build();
        when(activations.findByUserIdAndHostIdAndPackageId(alice, hostId, pkg.getId()))
                .thenReturn(Optional.of(activation));
        when(activations.findByUserIdAndHostIdOrderByCreatedAtAsc(alice, hostId))
                .thenReturn(List.of(activation));
        when(activations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(projectFiles.write(any(), any(), any(), any())).thenReturn(true);
    }

    private static GovernancePackageFile file(String path, GovernanceFileKind kind, String content) {
        return GovernancePackageFile.builder().id(UUID.randomUUID()).path(path).kind(kind)
                .content(content).build();
    }

    private static GovernanceProjectDepositPlan only(GovernanceDepositPlan plan) {
        assertThat(plan.projects()).hasSize(1);
        return plan.projects().get(0);
    }

    // ------------------------------------------------------------- l'annonce

    @Test
    @DisplayName("l'annonce dit ce qui sera créé et ce qui sera laissé tel quel")
    void planTellsCreateAndKeep() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of("STATE.md")));

        GovernanceDepositPlan plan = service.plan(alice, host, pkg.getId());

        assertThat(plan.hostRef()).isEqualTo(hostId.toString());
        assertThat(plan.files()).extracting("path")
                .containsExactly("STATE.md", ".claude/skills/explique.md");
        GovernanceProjectDepositPlan project = only(plan);
        assertThat(project.readable()).isTrue();
        assertThat(project.entries()).extracting("path", "action").containsExactly(
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

        GovernanceProjectDepositPlan project = only(service.plan(alice, host, pkg.getId()));

        assertThat(project.readable()).isFalse();
        assertThat(project.entries()).allSatisfy(entry ->
                assertThat(entry.action()).isEqualTo(GovernanceDepositAction.UNKNOWN));
    }

    @Test
    @DisplayName("l'annonce couvre TOUS les dossiers du poste, un par un")
    void planCoversEveryProjectOfTheHost() {
        Workspace second = Workspace.builder().id(UUID.randomUUID()).userId(alice).name("api")
                .hostId(hostId).build();
        when(hostScope.projectsOf(alice, host)).thenReturn(List.of(workspace, second));
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of("STATE.md")));
        when(projectFiles.listPaths(alice, second)).thenReturn(Optional.of(Set.of()));

        GovernanceDepositPlan plan = service.plan(alice, host, pkg.getId());

        assertThat(plan.projects()).extracting("name").containsExactly("web", "api");
        assertThat(plan.projects().get(1).entries()).allSatisfy(entry ->
                assertThat(entry.action()).isEqualTo(GovernanceDepositAction.CREATE));
    }

    // --------------------------------------------------------------- le dépôt

    @Test
    @DisplayName("le dépôt crée ce qui manque et NE TOUCHE PAS à ce qui existe")
    void depositCreatesMissingAndNeverOverwrites() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of("STATE.md")));

        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        verify(projectFiles).write(alice, workspace, ".claude/skills/explique.md", "# explique");
        // Le fichier déjà présent n'est jamais réécrit, même si son contenu diffère du paquet.
        verify(projectFiles, never()).write(eq(alice), eq(workspace), eq("STATE.md"), any());
        assertThat(only(done).entries()).extracting("action")
                .containsExactly(GovernanceDepositAction.KEEP, GovernanceDepositAction.CREATE);
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.APPLIED);
        assertThat(activation.getAppliedAt()).isNotNull();
        // Le dépôt réaligne la version appliquée sans rien réécrire.
        assertThat(activation.getAppliedVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("le dépôt se pose dans CHAQUE dossier du poste")
    void depositReachesEveryProjectOfTheHost() {
        Workspace second = Workspace.builder().id(UUID.randomUUID()).userId(alice).name("api")
                .hostId(hostId).build();
        when(hostScope.projectsOf(alice, host)).thenReturn(List.of(workspace, second));
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of()));
        when(projectFiles.listPaths(alice, second)).thenReturn(Optional.of(Set.of()));

        service.deposit(alice, host, pkg.getId());

        verify(projectFiles).write(alice, workspace, "STATE.md", "# État");
        verify(projectFiles).write(alice, second, "STATE.md", "# État");
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.APPLIED);
    }

    @Test
    @DisplayName("un seul dossier illisible suffit à laisser l'activation du poste en attente")
    void oneUnreadableProjectKeepsTheHostPending() {
        Workspace second = Workspace.builder().id(UUID.randomUUID()).userId(alice).name("api")
                .hostId(hostId).build();
        when(hostScope.projectsOf(alice, host)).thenReturn(List.of(workspace, second));
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of()));
        when(projectFiles.listPaths(alice, second)).thenReturn(Optional.empty());

        service.deposit(alice, host, pkg.getId());

        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.PENDING);
    }

    @Test
    @DisplayName("le dépôt rejoué n'écrit plus rien")
    void secondDepositWritesNothing() {
        when(projectFiles.listPaths(alice, workspace))
                .thenReturn(Optional.of(Set.of("STATE.md", ".claude/skills/explique.md")));

        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        verify(projectFiles, never()).write(any(), any(), any(), any());
        assertThat(only(done).entries()).allSatisfy(entry ->
                assertThat(entry.action()).isEqualTo(GovernanceDepositAction.KEEP));
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.APPLIED);
    }

    @Test
    @DisplayName("une écriture refusée laisse l'activation en attente, les autres sont écrites")
    void partialFailureStaysPending() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of()));
        when(projectFiles.write(alice, workspace, "STATE.md", "# État")).thenReturn(false);

        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        verify(projectFiles).write(alice, workspace, ".claude/skills/explique.md", "# explique");
        assertThat(only(done).entries()).extracting("action")
                .containsExactly(GovernanceDepositAction.UNKNOWN, GovernanceDepositAction.CREATE);
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.PENDING);
        assertThat(activation.getAppliedAt()).isNull();
    }

    @Test
    @DisplayName("une machine éteinte n'est pas une erreur : le paquet reste actif, en attente")
    void unreachableMachineStaysPending() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.empty());

        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        assertThat(only(done).readable()).isFalse();
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.PENDING);
        verify(projectFiles, never()).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("un paquet sans fichier passe APPLIED tout de suite — il n'y a rien à attendre")
    void packageWithoutFilesIsAppliedImmediately() {
        when(packageService.filesOf(pkg.getId())).thenReturn(List.of());
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of()));

        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        assertThat(only(done).entries()).isEmpty();
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.APPLIED);
    }

    @Test
    @DisplayName("un poste sans dossier passe APPLIED : il n'y a rien à attendre")
    void hostWithoutProjectIsApplied() {
        when(hostScope.projectsOf(alice, host)).thenReturn(List.of());

        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        assertThat(done.projects()).isEmpty();
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.APPLIED);
        verify(projectFiles, never()).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("appliquer un paquet qui n'est pas actif sur ce poste est introuvable")
    void applyOnInactivePackageIsNotFound() {
        when(activations.findByUserIdAndHostIdAndPackageId(alice, hostId, pkg.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deposit(alice, host, pkg.getId()))
                .isInstanceOf(GovernancePackageNotFoundException.class);
        verify(projectFiles, never()).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("un chemin devenu invalide n'est jamais écrit, et laisse l'activation en attente")
    void invalidStoredPathIsNeverWritten() {
        when(packageService.filesOf(pkg.getId()))
                .thenReturn(List.of(file("../voisin/STATE.md", GovernanceFileKind.TEMPLATE, "x")));
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of()));

        service.deposit(alice, host, pkg.getId());

        verify(projectFiles, never()).write(any(), any(), any(), any());
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.PENDING);
    }

    // ------------------------------------------- le dossier ajouté demain

    @Test
    @DisplayName("un dossier neuf hérite des paquets actifs sur son poste, sans nouvelle activation")
    void newProjectInheritsFromItsHost() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of()));

        service.depositOnNewProjectQuietly(alice, workspaceId);

        verify(projectFiles).write(alice, workspace, "STATE.md", "# État");
        verify(projectFiles).write(alice, workspace, ".claude/skills/explique.md", "# explique");
    }

    @Test
    @DisplayName("un dossier neuf qu'on n'a pas su écrire remet l'activation du poste en attente")
    void newProjectFailureReopensPending() {
        activation.setStatus(GovernanceActivationStatus.APPLIED);
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.empty());

        service.depositOnNewProjectQuietly(alice, workspaceId);

        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.PENDING);
    }

    @Test
    @DisplayName("le dépôt silencieux ne lève jamais, même si le projet a disparu")
    void quietDepositNeverThrows() {
        when(hostScope.projectOf(alice, workspaceId))
                .thenThrow(new fr.claudegateway.atelier.WorkspaceNotFoundException(
                        "Projet introuvable."));

        service.depositOnNewProjectQuietly(alice, workspaceId);

        verify(projectFiles, never()).write(any(), any(), any(), any());
    }
}
