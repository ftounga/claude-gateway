package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import fr.claudegateway.governance.dto.GovernanceFileComparison;

/**
 * F-75 / SF-75-02 — lire avant d'accepter.
 *
 * <p>Ce test garde deux choses. D'abord qu'on <b>voit ce qu'on accepte</b> : le contenu apporté, et
 * ce que le dossier porte déjà — puisque c'est l'existant qui restera. Ensuite qu'on ne voit
 * <b>que cela</b> : un chemin absent du paquet n'ouvre pas une lecture du disque d'un client.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceFileReadingServiceTest {

    @Mock
    private GovernancePackageService packageService;
    @Mock
    private GovernanceProjectFiles projectFiles;
    @Mock
    private GovernanceHostScope hostScope;

    private GovernanceFileReadingService service;

    private final UUID alice = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(hostId);
    private Workspace workspace;
    private GovernancePackage pkg;

    @BeforeEach
    void setUp() {
        service = new GovernanceFileReadingService(packageService, projectFiles, hostScope);
        workspace = Workspace.builder().id(UUID.randomUUID()).userId(alice).name("web")
                .hostId(hostId).build();
        when(hostScope.projectsOf(alice, host)).thenReturn(List.of(workspace));

        pkg = GovernancePackage.builder().id(UUID.randomUUID()).slug("livrables").name("Livrables")
                .version(1).published(true).build();
        when(packageService.requirePublished(pkg.getId())).thenReturn(pkg);
        when(packageService.filesOf(pkg.getId())).thenReturn(List.of(
                GovernancePackageFile.builder().id(UUID.randomUUID()).path("STATE.md")
                        .kind(GovernanceFileKind.TEMPLATE).content("# Gabarit\n").build()));
    }

    @Test
    @DisplayName("un fichier absent du dossier sera créé : on rend le contenu apporté, sans existant")
    void missingFileIsAnnouncedWithItsContent() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of()));

        GovernanceFileComparison read = service.read(alice, host, pkg.getId(), "STATE.md");

        assertThat(read.content()).isEqualTo("# Gabarit\n");
        assertThat(read.truncated()).isFalse();
        assertThat(read.projects()).singleElement().satisfies(project -> {
            assertThat(project.exists()).isFalse();
            assertThat(project.identical()).isFalse();
            assertThat(project.content()).isNull();
        });
        verify(projectFiles, never()).read(any(), any(), any());
    }

    @Test
    @DisplayName("un fichier déjà là, différent : on rend son contenu ACTUEL — c'est lui qui restera")
    void existingDifferentFileIsShownAsItIs() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of("STATE.md")));
        when(projectFiles.read(alice, workspace, "STATE.md"))
                .thenReturn(Optional.of("# Mon état à moi\n"));

        GovernanceFileComparison read = service.read(alice, host, pkg.getId(), "STATE.md");

        assertThat(read.projects()).singleElement().satisfies(project -> {
            assertThat(project.exists()).isTrue();
            assertThat(project.identical()).isFalse();
            assertThat(project.content()).isEqualTo("# Mon état à moi\n");
        });
    }

    @Test
    @DisplayName("un fichier déjà identique est signalé comme tel : rien ne changerait")
    void identicalFileIsFlagged() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of("STATE.md")));
        when(projectFiles.read(alice, workspace, "STATE.md")).thenReturn(Optional.of("# Gabarit\n"));

        assertThat(service.read(alice, host, pkg.getId(), "STATE.md").projects())
                .singleElement()
                .satisfies(project -> assertThat(project.identical()).isTrue());
    }

    @Test
    @DisplayName("un dossier illisible ne prétend rien : ni existant, ni manquant")
    void unreadableProjectClaimsNothing() {
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.empty());

        assertThat(service.read(alice, host, pkg.getId(), "STATE.md").projects())
                .singleElement()
                .satisfies(project -> {
                    assertThat(project.readable()).isFalse();
                    assertThat(project.exists()).isFalse();
                    assertThat(project.content()).isNull();
                });
    }

    @Test
    @DisplayName("un chemin absent du paquet est introuvable, même s'il existe dans le dossier")
    void pathOutsideThePackageIsNotFound() {
        when(projectFiles.listPaths(alice, workspace))
                .thenReturn(Optional.of(Set.of(".env", "STATE.md")));

        assertThatThrownBy(() -> service.read(alice, host, pkg.getId(), ".env"))
                .isInstanceOf(GovernancePackageNotFoundException.class);
        verify(projectFiles, never()).read(any(), any(), any());
    }

    @Test
    @DisplayName("un chemin qui sortirait du dossier est refusé avant toute lecture")
    void escapingPathIsRefused() {
        assertThatThrownBy(() -> service.read(alice, host, pkg.getId(), "../voisin/.env"))
                .isInstanceOf(GovernancePackageNotFoundException.class);
        verify(projectFiles, never()).read(any(), any(), any());
    }

    @Test
    @DisplayName("un contenu trop long est coupé, et la coupe est DITE")
    void longContentIsTruncatedAndSaysSo() {
        String huge = "x".repeat(GovernanceFileReadingService.MAX_CONTENT_CHARS + 10);
        when(packageService.filesOf(pkg.getId())).thenReturn(List.of(
                GovernancePackageFile.builder().id(UUID.randomUUID()).path("STATE.md")
                        .kind(GovernanceFileKind.TEMPLATE).content(huge).build()));
        when(projectFiles.listPaths(alice, workspace)).thenReturn(Optional.of(Set.of("STATE.md")));
        when(projectFiles.read(alice, workspace, "STATE.md")).thenReturn(Optional.of(huge));

        GovernanceFileComparison read = service.read(alice, host, pkg.getId(), "STATE.md");

        assertThat(read.truncated()).isTrue();
        assertThat(read.content()).hasSize(GovernanceFileReadingService.MAX_CONTENT_CHARS);
        assertThat(read.projects()).singleElement()
                .satisfies(project -> assertThat(project.truncated()).isTrue());
    }

    @Test
    @DisplayName("au-delà du plafond, les dossiers non inspectés sont comptés et annoncés")
    void beyondTheCapProjectsAreCountedNotRead() {
        List<Workspace> many = new java.util.ArrayList<>();
        for (int i = 0; i < GovernanceFileReadingService.MAX_PROJECTS_INSPECTED + 3; i++) {
            many.add(Workspace.builder().id(UUID.randomUUID()).userId(alice).name("p" + i)
                    .hostId(hostId).build());
        }
        when(hostScope.projectsOf(alice, host)).thenReturn(many);
        when(projectFiles.listPaths(any(), any())).thenReturn(Optional.of(Set.of()));

        GovernanceFileComparison read = service.read(alice, host, pkg.getId(), "STATE.md");

        assertThat(read.projects())
                .hasSize(GovernanceFileReadingService.MAX_PROJECTS_INSPECTED);
        assertThat(read.omitted()).isEqualTo(3);
    }
}
