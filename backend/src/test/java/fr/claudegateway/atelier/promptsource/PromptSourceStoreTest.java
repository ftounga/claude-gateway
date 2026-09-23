package fr.claudegateway.atelier.promptsource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Le cache des sources de la consigne (F-148 / SF-148-06).
 *
 * <p>Ce qu'il protège avant tout, comme le magasin de carte : <b>un échec de lecture ne détruit
 * rien</b>, et une rafale de tours ne martèle pas la machine du client.</p>
 */
class PromptSourceStoreTest {

    private static final Predicate<String> IS_SKILL = path -> path.startsWith(".claude/skills/");
    private static final List<String> CORE = List.of("CLAUDE.md", "STATE.md", "PLAN-ACTION.md");

    private final RunnerToolGateway runner = mock(RunnerToolGateway.class);
    private final PromptSourceFileRepository files = mock(PromptSourceFileRepository.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private PromptSourceStore store;

    private Workspace runnerWorkspace() {
        return Workspace.builder().id(workspaceId).userId(userId).name("projet").hostId(hostId)
                .projectPath("").executionTarget(WorkspaceExecutionTarget.RUNNER).build();
    }

    @BeforeEach
    void setUp() {
        store = new PromptSourceStore(runner, files);
        when(files.findByUserIdAndWorkspaceIdAndPath(any(), any(), any())).thenReturn(Optional.empty());
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private static RunnerCallResult unreachable() {
        return RunnerCallResult.backendError("runner_unavailable", "muet");
    }

    @Test
    @DisplayName("range l'arborescence, les fichiers cœur et les skills, chacun avec son empreinte")
    void storesTreeCoreFilesAndSkills() {
        when(runner.listFiles(any(RunnerTarget.class), any()))
                .thenReturn(ok("CLAUDE.md\n.claude/skills/foo.md\nsrc/App.java"));
        when(runner.readFile(any(RunnerTarget.class), any(), any()))
                .thenAnswer(inv -> ok("contenu de " + inv.getArgument(2)));

        store.refresh(userId, runnerWorkspace(), CORE, IS_SKILL, 15);

        // Arborescence + CLAUDE.md + STATE.md + PLAN-ACTION.md + le skill = 5 lignes rangées.
        verify(files, times(5)).save(any());
        verify(runner).readFile(any(RunnerTarget.class), any(), eq("CLAUDE.md"));
        verify(runner).readFile(any(RunnerTarget.class), any(), eq(".claude/skills/foo.md"));
        // src/App.java n'est pas un skill : jamais rangé.
        verify(runner, never()).readFile(any(RunnerTarget.class), any(), eq("src/App.java"));
    }

    @Test
    @DisplayName("un contenu inchangé n'est pas réécrit : seule la date d'observation bouge")
    void unchangedContentIsNotRewritten() {
        String content = "# Conventions\n";
        when(runner.listFiles(any(RunnerTarget.class), any())).thenReturn(ok(""));
        when(runner.readFile(any(RunnerTarget.class), any(), eq("CLAUDE.md"))).thenReturn(ok(content));
        when(runner.readFile(any(RunnerTarget.class), any(), eq("STATE.md"))).thenReturn(unreachable());
        when(runner.readFile(any(RunnerTarget.class), any(), eq("PLAN-ACTION.md"))).thenReturn(unreachable());
        PromptSourceFile existing = PromptSourceFile.builder()
                .userId(userId).hostId(hostId).workspaceId(workspaceId).path("CLAUDE.md")
                .content("ancien").digest(digestOf(content)).build();
        when(files.findByUserIdAndWorkspaceIdAndPath(userId, workspaceId, "CLAUDE.md"))
                .thenReturn(Optional.of(existing));

        store.refresh(userId, runnerWorkspace(), CORE, IS_SKILL, 15);

        // L'empreinte a suffi à conclure : le contenu n'a pas été remplacé.
        assertThat(existing.getContent()).isEqualTo("ancien");
        assertThat(existing.getObservedAt()).isNotNull();
    }

    @Test
    @DisplayName("deux rafraîchissements rapprochés ne lisent la machine qu'une fois")
    void twoQuickRefreshesReadTheMachineOnce() {
        when(runner.listFiles(any(RunnerTarget.class), any())).thenReturn(ok(""));
        when(runner.readFile(any(RunnerTarget.class), any(), any())).thenReturn(ok("x"));

        store.refresh(userId, runnerWorkspace(), CORE, IS_SKILL, 15);
        store.refresh(userId, runnerWorkspace(), CORE, IS_SKILL, 15);

        verify(runner, times(1)).listFiles(any(RunnerTarget.class), any());
    }

    @Test
    @DisplayName("une machine muette ne détruit RIEN : rien n'est rangé")
    void asilentMachineDestroysNothing() {
        when(runner.listFiles(any(RunnerTarget.class), any())).thenReturn(unreachable());
        when(runner.readFile(any(RunnerTarget.class), any(), any())).thenReturn(unreachable());

        store.refresh(userId, runnerWorkspace(), CORE, IS_SKILL, 15);

        verify(files, never()).save(any());
    }

    @Test
    @DisplayName("un projet en cible SANDBOX ne déclenche aucun appel machine")
    void sandboxTargetTouchesNothing() {
        Workspace sandbox = Workspace.builder().id(workspaceId).userId(userId).name("projet")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build();

        store.refresh(userId, sandbox, CORE, IS_SKILL, 15);

        verify(runner, never()).listFiles(any(RunnerTarget.class), any());
        verify(runner, never()).readFile(any(RunnerTarget.class), any(), any());
    }

    @Test
    @DisplayName("lecture, arborescence et amorçage portent toujours l'utilisateur ET le projet")
    void readsAlwaysCarryBothUserAndWorkspace() {
        store.read(userId, workspaceId, "CLAUDE.md");
        verify(files).findByUserIdAndWorkspaceIdAndPath(eq(userId), eq(workspaceId), eq("CLAUDE.md"));

        assertThat(store.read(null, workspaceId, "CLAUDE.md")).isEmpty();
        assertThat(store.read(userId, null, "CLAUDE.md")).isEmpty();
        assertThat(store.tree(null, workspaceId)).isEmpty();
        assertThat(store.isPrimed(null, workspaceId)).isFalse();
        assertThat(store.isPrimed(userId, null)).isFalse();
    }

    @Test
    @DisplayName("read rend le contenu rangé ; tree le découpe ligne par ligne")
    void readAndTreeServeTheStoredCopy() {
        when(files.findByUserIdAndWorkspaceIdAndPath(userId, workspaceId, "CLAUDE.md"))
                .thenReturn(Optional.of(PromptSourceFile.builder().content("# Conventions").build()));
        when(files.findByUserIdAndWorkspaceIdAndPath(userId, workspaceId, PromptSourceStore.TREE_PATH))
                .thenReturn(Optional.of(PromptSourceFile.builder().content("a.md\nb.md").build()));

        assertThat(store.read(userId, workspaceId, "CLAUDE.md")).contains("# Conventions");
        assertThat(store.tree(userId, workspaceId)).containsExactly("a.md", "b.md");
    }

    private static String digestOf(String content) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
