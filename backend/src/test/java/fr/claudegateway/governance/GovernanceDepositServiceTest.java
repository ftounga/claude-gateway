package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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
    private GovernanceHostFiles hostFiles;
    @Mock
    private GovernanceHostScope hostScope;

    @Mock
    private GovernanceDepositedFileRepository deposited;

    private GovernanceDepositService service;

    private final UUID alice = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(hostId);
    private final UUID workspaceId = UUID.randomUUID();
    private Workspace workspace;
    private GovernancePackage pkg;
    private GovernanceActivation activation;

    /** Les empreintes retenues, en mémoire — le pendant de {@code governance_deposited_files}. */
    private final List<GovernanceDepositedFile> prints = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new GovernanceDepositService(activations, packageService, projectFiles, hostFiles,
                hostScope, deposited);
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

        // Les EMPREINTES (F-96 / SF-96-01) sont tenues en mémoire : c'est ce qui permet de rejouer
        // un dépôt dans un test comme il se rejoue en vrai — la deuxième passe doit retrouver ce que
        // la première a écrit, sinon on ne teste jamais la reconnaissance d'un artefact intact.
        when(deposited.save(any())).thenAnswer(invocation -> {
            GovernanceDepositedFile print = invocation.getArgument(0);
            prints.removeIf(existing -> existing.getWorkspaceId().equals(print.getWorkspaceId())
                    && existing.getPath().equals(print.getPath()));
            prints.add(print);
            return print;
        });
        when(deposited.findByUserIdAndHostIdAndPackageId(any(), any(), any()))
                .thenAnswer(invocation -> List.copyOf(prints));
    }

    private static GovernancePackageFile file(String path, GovernanceFileKind kind, String content) {
        return GovernancePackageFile.builder().id(UUID.randomUUID()).path(path).kind(kind)
                .content(content).build();
    }

    /** Un fichier que le paquet pose une fois et ne met JAMAIS à jour : du contenu utilisateur. */
    private static GovernancePackageFile userContent(String path, String content) {
        return GovernancePackageFile.builder().id(UUID.randomUUID()).path(path)
                .kind(GovernanceFileKind.TEMPLATE).content(content).generated(false).build();
    }

    /** Ce que le dossier porte aujourd'hui sous ce chemin. */
    private void onDisk(String path, String content) {
        when(projectFiles.readExact(alice, workspace, path)).thenReturn(Optional.ofNullable(content));
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
        onDisk("STATE.md", "# État"); // déjà exactement ce que le paquet apporte



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
        // …et ne retient AUCUNE empreinte : ouvrir un écran ne doit pas changer ce que le dépôt
        // suivant décidera.
        verify(deposited, never()).save(any());
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
        // Rempli par l'utilisateur, et aucune empreinte ne dit qu'il vient de nous : c'est du
        // contenu utilisateur, il est CONSERVÉ — et l'annonce le dit (F-96).
        onDisk("STATE.md", "# État\n\nSujet : migration DNS");

        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        verify(projectFiles).write(alice, workspace, ".claude/skills/explique.md", "# explique");
        // Le fichier déjà présent n'est jamais réécrit, même si son contenu diffère du paquet.
        verify(projectFiles, never()).write(eq(alice), eq(workspace), eq("STATE.md"), any());
        assertThat(only(done).entries()).extracting("action")
                .containsExactly(GovernanceDepositAction.KEEP_LOCAL, GovernanceDepositAction.CREATE);
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
    @DisplayName("le dépôt rejoué n'écrit plus rien — et ne relit même pas la machine")
    void secondDepositWritesNothing() {
        when(projectFiles.listPaths(alice, workspace))
                .thenReturn(Optional.of(Set.of()))
                .thenReturn(Optional.of(Set.of("STATE.md", ".claude/skills/explique.md")));

        service.deposit(alice, host, pkg.getId());
        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        // Deux écritures au premier passage, AUCUNE au second.
        verify(projectFiles, times(2)).write(any(), any(), any(), any());
        assertThat(only(done).entries()).allSatisfy(entry ->
                assertThat(entry.action()).isEqualTo(GovernanceDepositAction.KEEP));
        // Le cas courant doit rester GRATUIT : l'empreinte retenue suffit à conclure, sans
        // aller-retour vers la machine.
        verify(projectFiles, never()).readExact(any(), any(), any());
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

    // ------------------------------- F-96 : la gouvernance se met a jour

    /** Le paquet republie un contenu different au chemin donne. */
    private void republish(String path, GovernanceFileKind kind, String content) {
        when(packageService.filesOf(pkg.getId())).thenReturn(List.of(file(path, kind, content)));
    }

    @Test
    @DisplayName("un artefact RESTÉ INTACT est mis à jour quand le paquet change")
    void updatesAnUntouchedArtefact() {
        when(projectFiles.listPaths(alice, workspace))
                .thenReturn(Optional.of(Set.of()))
                .thenReturn(Optional.of(Set.of(".claude/skills/explique.md")));
        republish(".claude/skills/explique.md", GovernanceFileKind.SKILL, "# explique");

        service.deposit(alice, host, pkg.getId()); // le poste reçoit la v1

        // La machine porte toujours exactement ce qu'on lui avait déposé…
        onDisk(".claude/skills/explique.md", "# explique");
        republish(".claude/skills/explique.md", GovernanceFileKind.SKILL, "# explique, corrigé");

        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        // …donc la correction ARRIVE. C'est tout l'objet de F-96.
        verify(projectFiles).write(alice, workspace, ".claude/skills/explique.md",
                "# explique, corrigé");
        assertThat(only(done).entries()).extracting("action")
                .containsExactly(GovernanceDepositAction.UPDATE);
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.APPLIED);
    }

    @Test
    @DisplayName("un artefact MODIFIÉ LOCALEMENT est conservé — et l'écran doit pouvoir le dire")
    void keepsAnArtefactModifiedLocally() {
        when(projectFiles.listPaths(alice, workspace))
                .thenReturn(Optional.of(Set.of()))
                .thenReturn(Optional.of(Set.of("STATE.md")));
        republish("STATE.md", GovernanceFileKind.TEMPLATE, "# État");

        service.deposit(alice, host, pkg.getId());

        // Quelqu'un a écrit dedans : le gabarit est devenu le journal d'un sujet.
        onDisk("STATE.md", "# État\n\n## Où j'en suis\n\nLe VPN tombe toutes les 20 min.");
        republish("STATE.md", GovernanceFileKind.TEMPLATE, "# État\n\n## Statut\n\n`en cours`");

        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        // JAMAIS écrasé : ce serait une perte de données déclenchée par un clic. Une seule
        // écriture au total — celle du premier dépôt, quand le fichier n'existait pas encore.
        verify(projectFiles, times(1)).write(any(), any(), any(), any());
        assertThat(only(done).entries()).extracting("action")
                .containsExactly(GovernanceDepositAction.KEEP_LOCAL);
        // Et rien ne reste « en attente » : ce fichier ne sera jamais déposé, c'est décidé.
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.APPLIED);
    }

    @Test
    @DisplayName("un fichier d'ORIGINE INCONNUE est du contenu utilisateur : conservé, et dit")
    void keepsAFileOfUnknownOrigin() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of("STATE.md")));
        republish("STATE.md", GovernanceFileKind.TEMPLATE, "# État");
        onDisk("STATE.md", "# Mon état à moi");

        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        verify(projectFiles, never()).write(any(), any(), any(), any());
        assertThat(only(done).entries()).extracting("action")
                .containsExactly(GovernanceDepositAction.KEEP_LOCAL);
    }

    @Test
    @DisplayName("un fichier déclaré CONTENU UTILISATEUR n'est ni relu ni mis à jour")
    void neverTouchesADeclaredUserContentFile() {
        when(packageService.filesOf(pkg.getId()))
                .thenReturn(List.of(userContent("acces.md", "# Accès")));
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of("acces.md")));

        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        assertThat(only(done).entries()).extracting("action")
                .containsExactly(GovernanceDepositAction.KEEP);
        verify(projectFiles, never()).write(any(), any(), any(), any());
        // Pas même une lecture : le paquet a déclaré qu'il n'y reviendrait pas.
        verify(projectFiles, never()).readExact(any(), any(), any());
    }

    @Test
    @DisplayName("des FINS DE LIGNE réécrites ne sont pas une modification")
    void lineEndingsAreNotAModification() {
        when(projectFiles.listPaths(alice, workspace))
                .thenReturn(Optional.of(Set.of()))
                .thenReturn(Optional.of(Set.of("STATE.md")));
        republish("STATE.md", GovernanceFileKind.TEMPLATE, "# État\n\n## Le sujet\n");

        service.deposit(alice, host, pkg.getId());

        // Le poste est sous Windows : le fichier revient en CRLF, sans que personne n'y ait touché.
        onDisk("STATE.md", "# État\r\n\r\n## Le sujet\r\n");
        republish("STATE.md", GovernanceFileKind.TEMPLATE, "# État\n\n## Statut\n");

        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        assertThat(only(done).entries()).extracting("action")
                .containsExactly(GovernanceDepositAction.UPDATE);
        verify(projectFiles).write(alice, workspace, "STATE.md", "# État\n\n## Statut\n");
    }

    @Test
    @DisplayName("un fichier présent mais ILLISIBLE n'est jamais écrasé : on ne sait pas")
    void anUnreadableExistingFileIsNeverOverwritten() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of("STATE.md")));
        republish("STATE.md", GovernanceFileKind.TEMPLATE, "# État");
        onDisk("STATE.md", null);

        GovernanceDepositPlan done = service.deposit(alice, host, pkg.getId());

        assertThat(only(done).entries()).extracting("action")
                .containsExactly(GovernanceDepositAction.UNKNOWN);
        verify(projectFiles, never()).write(any(), any(), any(), any());
        // Le fichier EST là : ne rien savoir de sa fraîcheur ne retient pas l'activation.
        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.APPLIED);
    }

    @Test
    @DisplayName("un dossier ajouté demain n'écrase RIEN : le geste appartient à l'utilisateur")
    void aNewProjectNeverUpdates() {
        when(projectFiles.listPaths(alice, workspace))
                .thenReturn(Optional.of(Set.of()))
                .thenReturn(Optional.of(Set.of(".claude/skills/explique.md")));
        republish(".claude/skills/explique.md", GovernanceFileKind.SKILL, "# explique");

        service.deposit(alice, host, pkg.getId());

        onDisk(".claude/skills/explique.md", "# explique");
        republish(".claude/skills/explique.md", GovernanceFileKind.SKILL, "# explique, corrigé");

        service.depositOnNewProjectQuietly(alice, workspaceId);

        // Rien n'a été réécrit : un dépôt automatique ne met jamais à jour, même un artefact intact.
        verify(projectFiles, times(1)).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("l'annonce dit « sera mis à jour » — sans rien écrire ni rien retenir")
    void planAnnouncesTheUpdate() {
        when(projectFiles.listPaths(alice, workspace))
                .thenReturn(Optional.of(Set.of()))
                .thenReturn(Optional.of(Set.of("STATE.md")));
        republish("STATE.md", GovernanceFileKind.TEMPLATE, "# État");

        service.deposit(alice, host, pkg.getId());

        onDisk("STATE.md", "# État");
        republish("STATE.md", GovernanceFileKind.TEMPLATE, "# État v2");

        GovernanceDepositPlan plan = service.plan(alice, host, pkg.getId());

        assertThat(only(plan).entries()).extracting("action")
                .containsExactly(GovernanceDepositAction.UPDATE);
        // Une seule écriture au total : celle du premier dépôt. L'annonce n'écrit pas.
        verify(projectFiles, times(1)).write(any(), any(), any(), any());
    }
}
