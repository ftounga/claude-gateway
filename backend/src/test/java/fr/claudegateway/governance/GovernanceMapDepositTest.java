package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import fr.claudegateway.governance.GovernanceHostFiles.Presence;
import fr.claudegateway.governance.dto.GovernanceDepositAction;
import fr.claudegateway.governance.dto.GovernanceDepositPlan;

/**
 * F-92 / SF-92-01 — <b>la carte se pose à la racine du poste</b>, une seule fois, et ne s'écrase
 * jamais.
 *
 * <p>Trois promesses sont vérifiées ici, et nulle part ailleurs :</p>
 * <ol>
 *   <li><b>le genre décide du lieu</b> — un {@code MAP} ne part pas dans un projet, un
 *       {@code TEMPLATE} ne part pas à la racine ; les confondre recopierait la carte dans chaque
 *       dossier, c'est-à-dire l'exact contraire de ce que F-92 apporte ;</li>
 *   <li><b>le doute n'écrit pas</b> — seule une réponse « ce fichier n'existe pas » autorise une
 *       écriture. Toute autre issue laisse la carte intacte, parce qu'écrire signifierait
 *       <b>écraser</b> la carte d'un client ;</li>
 *   <li><b>un poste sans machine n'attend pas une racine qui n'existera jamais</b> — le poste
 *       « Hébergé » ne retient pas l'activation en attente.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceMapDepositTest {

    @Mock
    private GovernanceActivationRepository activations;
    @Mock
    private GovernancePackageService packageService;
    @Mock
    private GovernanceProjectFiles projectFiles;
    @Mock
    private GovernanceHostFiles hostFiles;
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
        service = new GovernanceDepositService(activations, packageService, projectFiles, hostFiles,
                hostScope);
        workspace = Workspace.builder().id(workspaceId).userId(alice).name("migration-dns")
                .hostId(hostId).projectPath("migration-dns").build();
        when(hostScope.projectsOf(alice, host)).thenReturn(List.of(workspace));
        when(hostScope.nameOf(alice, host)).thenReturn("FREE");

        pkg = GovernancePackage.builder().id(UUID.randomUUID()).slug("savoir-durable")
                .name("Le savoir durable").version(2).published(true).rules("Des faits, datés.")
                .build();
        when(packageService.requirePublished(pkg.getId())).thenReturn(pkg);
        when(packageService.filesOf(pkg.getId())).thenReturn(List.of(
                file("README.md", GovernanceFileKind.MAP, "# La carte du poste"),
                file("acces.md", GovernanceFileKind.MAP, "# Accès"),
                file("STATE.md", GovernanceFileKind.TEMPLATE, "# État")));

        activation = GovernanceActivation.builder().id(UUID.randomUUID()).userId(alice)
                .hostId(hostId).packageId(pkg.getId()).appliedVersion(1)
                .status(GovernanceActivationStatus.PENDING).build();
        when(activations.findByUserIdAndHostIdAndPackageId(alice, hostId, pkg.getId()))
                .thenReturn(Optional.of(activation));
        when(activations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of()));
        when(projectFiles.write(any(), any(), any(), any())).thenReturn(true);
        when(hostFiles.supports(host)).thenReturn(true);
        when(hostFiles.write(any(), any(), any(), any())).thenReturn(true);
    }

    private static GovernancePackageFile file(String path, GovernanceFileKind kind, String content) {
        return GovernancePackageFile.builder().id(UUID.randomUUID()).path(path).kind(kind)
                .content(content).build();
    }

    // ------------------------------------------------------------- l'annonce

    @Test
    @DisplayName("l'annonce distingue la RACINE des projets, et n'écrit rien")
    void planSeparatesRootFromProjects() {
        when(hostFiles.presence(alice, host, "README.md")).thenReturn(Presence.PRESENT);
        when(hostFiles.presence(alice, host, "acces.md")).thenReturn(Presence.ABSENT);

        GovernanceDepositPlan plan = service.plan(alice, host, pkg.getId());

        assertThat(plan.root().supported()).isTrue();
        assertThat(plan.root().readable()).isTrue();
        assertThat(plan.root().message()).isNull();
        assertThat(plan.root().entries()).extracting("path", "action").containsExactly(
                org.assertj.core.groups.Tuple.tuple("README.md", GovernanceDepositAction.KEEP),
                org.assertj.core.groups.Tuple.tuple("acces.md", GovernanceDepositAction.CREATE));
        // Le projet ne reçoit QUE le gabarit : la carte n'a rien à faire dans un dossier de travail.
        assertThat(plan.projects()).hasSize(1);
        assertThat(plan.projects().get(0).entries()).extracting("path").containsExactly("STATE.md");
        verify(hostFiles, never()).write(any(), any(), any(), any());
        verify(projectFiles, never()).write(any(), any(), any(), any());
    }

    // --------------------------------------------------------------- le dépôt

    @Test
    @DisplayName("le dépôt crée la carte à la racine, et seulement ce qui manque")
    void depositCreatesTheMissingMapFiles() {
        when(hostFiles.presence(alice, host, "README.md")).thenReturn(Presence.ABSENT);
        when(hostFiles.presence(alice, host, "acces.md")).thenReturn(Presence.PRESENT);

        GovernanceDepositPlan plan = service.deposit(alice, host, pkg.getId());

        verify(hostFiles).write(alice, host, "README.md", "# La carte du poste");
        verify(hostFiles, never()).write(eq(alice), eq(host), eq("acces.md"), any());
        assertThat(plan.root().entries()).extracting("action").containsExactly(
                GovernanceDepositAction.CREATE, GovernanceDepositAction.KEEP);
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.APPLIED);
    }

    @Test
    @DisplayName("un fichier de carte DÉJÀ LÀ n'est jamais réécrit — c'est la promesse qui protège")
    void anExistingMapFileIsNeverOverwritten() {
        when(hostFiles.presence(any(), any(), any())).thenReturn(Presence.PRESENT);

        service.deposit(alice, host, pkg.getId());

        verify(hostFiles, never()).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("une lecture INCONCLUSIVE n'écrit rien et laisse l'activation en attente")
    void anInconclusiveReadWritesNothing() {
        when(hostFiles.presence(alice, host, "README.md")).thenReturn(Presence.UNKNOWN);
        when(hostFiles.presence(alice, host, "acces.md")).thenReturn(Presence.UNKNOWN);

        GovernanceDepositPlan plan = service.deposit(alice, host, pkg.getId());

        verify(hostFiles, never()).write(any(), any(), any(), any());
        assertThat(plan.root().entries()).extracting("action")
                .containsOnly(GovernanceDepositAction.UNKNOWN);
        assertThat(plan.root().readable()).isFalse();
        // Le message porte SON ACTION CORRECTIVE : il est lu par quelqu'un qui doit réparer.
        assertThat(plan.root().message()).contains("lancez le runner").contains("Appliquer")
                .contains("Rien n'a été écrit");
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.PENDING);
    }

    @Test
    @DisplayName("une écriture refusée par la machine laisse l'activation en attente")
    void arefusedWriteKeepsTheActivationPending() {
        when(hostFiles.presence(any(), any(), any())).thenReturn(Presence.ABSENT);
        when(hostFiles.write(any(), any(), any(), any())).thenReturn(false);

        GovernanceDepositPlan plan = service.deposit(alice, host, pkg.getId());

        assertThat(plan.root().entries()).extracting("action")
                .containsOnly(GovernanceDepositAction.UNKNOWN);
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.PENDING);
    }

    // ------------------------------------------------------------- « Hébergé »

    @Test
    @DisplayName("le poste « Hébergé » n'a pas de racine : rien n'est tenté, et rien n'est retenu")
    void theHostedHostHasNoRoot() {
        GovernanceHostRef hosted = GovernanceHostRef.HOSTED;
        when(hostFiles.supports(hosted)).thenReturn(false);
        when(hostScope.projectsOf(alice, hosted)).thenReturn(List.of());
        when(hostScope.nameOf(alice, hosted)).thenReturn("Hébergé");
        when(activations.findByUserIdAndHostIdAndPackageId(alice, GovernanceHostRef.HOSTED.hostId(),
                pkg.getId())).thenReturn(Optional.of(activation));

        GovernanceDepositPlan plan = service.deposit(alice, hosted, pkg.getId());

        assertThat(plan.root().supported()).isFalse();
        assertThat(plan.root().message()).contains("Connectez une machine");
        verify(hostFiles, never()).write(any(), any(), any(), any());
        verify(hostFiles, never()).presence(any(), any(), any());
        // Et surtout : l'activation N'EST PAS retenue en attente d'une racine qui n'existera jamais.
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.APPLIED);
    }

    // --------------------------------------------- un dossier ajouté plus tard

    @Test
    @DisplayName("un projet neuf reçoit les gabarits, JAMAIS la carte")
    void aNewProjectNeverReceivesTheMap() {
        when(hostScope.projectOf(alice, workspaceId)).thenReturn(workspace);
        when(hostScope.hostOf(workspace)).thenReturn(host);
        when(activations.findByUserIdAndHostIdOrderByCreatedAtAsc(alice, hostId))
                .thenReturn(List.of(activation));

        service.depositOnNewProjectQuietly(alice, workspaceId);

        verify(projectFiles).write(alice, workspace, "STATE.md", "# État");
        verify(projectFiles, never()).write(eq(alice), eq(workspace), eq("README.md"), any());
        verify(projectFiles, never()).write(eq(alice), eq(workspace), eq("acces.md"), any());
        verifyNoInteractions(hostFiles);
    }
}
