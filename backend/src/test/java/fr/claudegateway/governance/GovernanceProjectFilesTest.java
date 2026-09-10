package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * F-51 / SF-51-03 — les fichiers d'un paquet se déposent <b>là où les fichiers du projet vivent</b>.
 *
 * <p>L'erreur qu'on veut rendre impossible : écrire en stockage un projet qui vit sur la machine de
 * l'utilisateur. Elle ne se verrait pas — le dépôt « réussirait », et rien n'apparaîtrait sur le
 * disque de personne.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceProjectFilesTest {

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private RunnerToolGateway runnerToolGateway;

    private GovernanceProjectFiles files;

    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        files = new GovernanceProjectFiles(workspaceService, runnerToolGateway);
    }

    /** Réponse d'outil réussie, dans la forme exacte du contrat runner. */
    private static RunnerCallResult okResult(String content) {
        return new RunnerCallResult(true, content, false, null, 0L, null, null, null, "", false);
    }

    private Workspace storageProject() {
        return Workspace.builder().id(UUID.randomUUID()).userId(alice).name("web").build();
    }

    private Workspace runnerProject() {
        return Workspace.builder().id(UUID.randomUUID()).userId(alice).name("web")
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build();
    }

    @Test
    @DisplayName("en cible stockage, tout passe par le stockage — aucun appel runner")
    void storageTargetNeverCallsTheRunner() {
        Workspace workspace = storageProject();
        when(workspaceService.tree(alice, workspace.getId())).thenReturn(List.of("STATE.md"));

        assertThat(files.listPaths(alice, workspace)).contains(java.util.Set.of("STATE.md"));
        assertThat(files.write(alice, workspace, "a.md", "x")).isTrue();

        verify(workspaceService).writeFile(alice, workspace.getId(), "a.md", "x");
        verify(runnerToolGateway, never()).listFiles(any(), anyString());
        verify(runnerToolGateway, never()).writeFile(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("en cible runner, tout passe par la machine — aucune écriture en stockage")
    void runnerTargetNeverWritesToStorage() {
        Workspace workspace = runnerProject();
        when(runnerToolGateway.listFiles(any(), anyString()))
                .thenReturn(okResult("STATE.md\nREADME.md"));
        when(runnerToolGateway.writeFile(any(), anyString(), anyString(), anyString()))
                .thenReturn(okResult("écrit"));

        assertThat(files.listPaths(alice, workspace))
                .contains(java.util.Set.of("STATE.md", "README.md"));
        assertThat(files.write(alice, workspace, "a.md", "x")).isTrue();

        verify(workspaceService, never()).writeFile(any(), any(), anyString(), anyString());
        verify(workspaceService, never()).tree(any(), any());
    }

    @Test
    @DisplayName("un projet illisible n'est jamais rendu comme un projet vide")
    void unreadableIsNotEmpty() {
        Workspace workspace = runnerProject();
        when(runnerToolGateway.listFiles(any(), anyString()))
                .thenReturn(RunnerCallResult.backendError("RUNNER_UNAVAILABLE", "Aucune machine connectée."));

        assertThat(files.listPaths(alice, workspace)).isEmpty();
    }

    @Test
    @DisplayName("un dossier vide est un état normal, et se distingue d'une lecture ratée")
    void emptyProjectIsReadable() {
        Workspace workspace = runnerProject();
        when(runnerToolGateway.listFiles(any(), anyString())).thenReturn(okResult(""));

        assertThat(files.listPaths(alice, workspace)).contains(java.util.Set.of());
    }

    @Test
    @DisplayName("une écriture refusée par la machine rend faux, sans lever")
    void refusedWriteReturnsFalse() {
        Workspace workspace = runnerProject();
        when(runnerToolGateway.writeFile(any(), anyString(), anyString(), anyString()))
                .thenReturn(RunnerCallResult.backendError("PATH_EXCLUDED", "Chemin exclu."));

        assertThat(files.write(alice, workspace, "secret.env", "x")).isFalse();
    }

    @Test
    @DisplayName("un stockage qui refuse l'écriture rend faux, sans lever")
    void refusedStorageWriteReturnsFalse() {
        Workspace workspace = storageProject();
        org.mockito.Mockito.doThrow(new fr.claudegateway.atelier.LocalWorkspaceException("non"))
                .when(workspaceService).writeFile(any(), any(), anyString(), anyString());

        assertThat(files.write(alice, workspace, "a.md", "x")).isFalse();
    }
}
