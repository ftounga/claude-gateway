package fr.claudegateway.atelier.repoindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * L'index de repo persistant (F-148 / SF-148-07).
 *
 * <p>Ce qu'il protège : <b>un échec ne détruit rien</b>, une rafale ne martèle pas la machine, et un
 * repo trop gros n'est jamais servi à moitié.</p>
 */
class RepoIndexStoreTest {

    private final RunnerToolGateway runner = mock(RunnerToolGateway.class);
    private final RepoIndexPathRepository repo = mock(RepoIndexPathRepository.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private RepoIndexStore store;

    private Workspace runnerWorkspace() {
        return Workspace.builder().id(workspaceId).userId(userId).name("projet").hostId(hostId)
                .projectPath("").executionTarget(WorkspaceExecutionTarget.RUNNER).build();
    }

    @BeforeEach
    void setUp() {
        store = new RepoIndexStore(runner, repo);
        when(repo.findByUserIdAndWorkspaceId(any(), any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    @Test
    @DisplayName("range la liste des chemins listés")
    void storesTheListedPaths() {
        when(runner.listFiles(any(RunnerTarget.class), any()))
                .thenReturn(ok("src/App.java\nREADME.md"));

        store.refresh(userId, runnerWorkspace());

        org.mockito.ArgumentCaptor<RepoIndexEntry> saved =
                org.mockito.ArgumentCaptor.forClass(RepoIndexEntry.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getWorkspaceId()).isEqualTo(workspaceId);
        assertThat(saved.getValue().getPathCount()).isEqualTo(2);
        assertThat(saved.getValue().getPaths()).contains("src/App.java");
    }

    @Test
    @DisplayName("deux rafraîchissements rapprochés ne lisent la machine qu'une fois")
    void twoQuickRefreshesReadOnce() {
        when(runner.listFiles(any(RunnerTarget.class), any())).thenReturn(ok("a.md"));

        store.refresh(userId, runnerWorkspace());
        store.refresh(userId, runnerWorkspace());

        verify(runner, times(1)).listFiles(any(RunnerTarget.class), any());
    }

    @Test
    @DisplayName("une machine muette ne détruit RIEN")
    void asilentMachineDestroysNothing() {
        when(runner.listFiles(any(RunnerTarget.class), any()))
                .thenReturn(RunnerCallResult.backendError("runner_unavailable", "muet"));

        store.refresh(userId, runnerWorkspace());

        verify(repo, never()).save(any());
        verify(repo, never()).deleteByUserIdAndWorkspaceId(any(), any());
    }

    @Test
    @DisplayName("un repo au-delà de la borne n'est pas indexé : l'index existant est effacé")
    void anOversizedRepoIsNotIndexed() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i <= RepoIndexStore.MAX_PATHS; i++) {
            huge.append("f").append(i).append('\n');
        }
        when(runner.listFiles(any(RunnerTarget.class), any())).thenReturn(ok(huge.toString()));

        store.refresh(userId, runnerWorkspace());

        verify(repo, never()).save(any());
        verify(repo).deleteByUserIdAndWorkspaceId(userId, workspaceId);
    }

    @Test
    @DisplayName("un projet en cible SANDBOX ne déclenche aucun appel machine")
    void sandboxTargetTouchesNothing() {
        Workspace sandbox = Workspace.builder().id(workspaceId).userId(userId).name("projet")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build();

        store.refresh(userId, sandbox);

        verify(runner, never()).listFiles(any(RunnerTarget.class), any());
    }

    @Test
    @DisplayName("glob depuis l'index : match du motif, base, ordre par chemin")
    void globMatchesAgainstTheIndex() {
        when(repo.findByUserIdAndWorkspaceId(userId, workspaceId))
                .thenReturn(Optional.of(RepoIndexEntry.builder()
                        .paths("src/util/Helper.java\nsrc/App.java\nREADME.md\nsrc/App.test.js")
                        .build()));

        // **/*.java trouve les .java à tout niveau, y compris à la racine (fallback **/).
        assertThat(store.glob(userId, workspaceId, "**/*.java", ""))
                .contains("src/App.java\nsrc/util/Helper.java");
        // *.md à la racine.
        assertThat(store.glob(userId, workspaceId, "*.md", "")).contains("README.md");
        // base = src, motif *.java (un seul segment) : seul src/App.java, pas le Helper imbriqué.
        assertThat(store.glob(userId, workspaceId, "*.java", "src")).contains("src/App.java");
        assertThat(store.glob(userId, workspaceId, "*.java", "src"))
                .get().asString().doesNotContain("Helper");
    }

    @Test
    @DisplayName("un motif à la racine matche aussi un fichier de la racine (**/)")
    void doubleStarMatchesRootFile() {
        when(repo.findByUserIdAndWorkspaceId(userId, workspaceId))
                .thenReturn(Optional.of(RepoIndexEntry.builder().paths("App.java\nsrc/Deep.java").build()));

        assertThat(store.glob(userId, workspaceId, "**/*.java", ""))
                .contains("App.java\nsrc/Deep.java");
    }

    @Test
    @DisplayName("index absent : glob rend vide (on retombera sur le runner)")
    void globOnMissingIndexIsEmpty() {
        assertThat(store.glob(userId, workspaceId, "**/*.java", "")).isEmpty();
    }

    @Test
    @DisplayName("lecture et amorçage portent toujours l'utilisateur ET le projet")
    void readsCarryBothUserAndWorkspace() {
        store.paths(userId, workspaceId);
        verify(repo).findByUserIdAndWorkspaceId(eq(userId), eq(workspaceId));

        assertThat(store.paths(null, workspaceId)).isEmpty();
        assertThat(store.paths(userId, null)).isEmpty();
        assertThat(store.isPrimed(null, workspaceId)).isFalse();
        assertThat(store.glob(null, workspaceId, "*", "")).isEmpty();
    }
}
