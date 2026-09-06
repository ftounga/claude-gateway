package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.AtelierEngineService.EngineStatus;
import fr.claudegateway.atelier.agent.AtelierAgentProperties;
import fr.claudegateway.atelier.git.GitWorkspaceService;
import fr.claudegateway.atelier.git.GitWorkspaceService.WorkspaceContent;
import fr.claudegateway.runner.RunnerStatusService;
import fr.claudegateway.runner.RunnerStatusService.RunnerStatus;

/**
 * Tests de la résolution du moteur d'un projet (F-39 / SF-39-07).
 *
 * <p>Deux règles y sont vérifiées séparément, parce qu'elles répondent à deux questions
 * différentes : <b>quel moteur</b> anime ce terminal (D1, D-L4-1 : la cible déclarée, jamais le
 * battement de cœur du runner) et <b>faut-il proposer le runner</b> (D6 : seulement sur une limite
 * du bac à sable réellement rencontrée).</p>
 */
@ExtendWith(MockitoExtension.class)
class AtelierEngineServiceTest {

    private static final int MOUNT_LIMIT = 300;

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private GitWorkspaceService gitWorkspaceService;
    @Mock
    private RunnerStatusService runnerStatusService;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private AtelierEngineService service() {
        return new AtelierEngineService(workspaceService, gitWorkspaceService, runnerStatusService,
                agentProperties());
    }

    private AtelierAgentProperties agentProperties() {
        return new AtelierAgentProperties(true, null, null, null, null, MOUNT_LIMIT, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private Workspace workspace(WorkspaceSource source, WorkspaceExecutionTarget target) {
        return Workspace.builder()
                .id(workspaceId).userId(userId).name("Projet")
                .source(source).executionTarget(target)
                .build();
    }

    private void givenWorkspace(WorkspaceSource source, WorkspaceExecutionTarget target) {
        when(workspaceService.requireOwned(userId, workspaceId))
                .thenReturn(workspace(source, target));
    }

    private void givenRunner(boolean connected, OffsetDateTime lastSeenAt) {
        when(runnerStatusService.status(userId, workspaceId))
                .thenReturn(new RunnerStatus(connected, lastSeenAt));
    }

    private void givenTree(int fileCount, boolean truncated) {
        List<String> files = new ArrayList<>(
                IntStream.range(0, fileCount).mapToObj(i -> "f" + i + ".txt").toList());
        when(gitWorkspaceService.tree(eq(userId), any(Workspace.class)))
                .thenReturn(new WorkspaceContent(files, truncated));
    }

    private EngineStatus status() {
        return service().status(userId, workspaceId);
    }

    // ---------- Quel moteur (D1 / D-L4-1) ----------

    @Test
    void runnerTargetRunsOnTheLocalMachine() {
        givenWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        givenRunner(true, OffsetDateTime.now());

        assertThat(status().engine()).isEqualTo(AtelierEngine.LOCAL_MACHINE);
    }

    @Test
    void sandboxTargetRunsOnTheHostedSandbox() {
        givenWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.SANDBOX);
        givenRunner(false, null);
        givenTree(3, false);

        assertThat(status().engine()).isEqualTo(AtelierEngine.HOSTED_SANDBOX);
    }

    @Test
    void missingTargetKeepsTheHistoricalSandboxBehaviour() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(
                Workspace.builder().id(workspaceId).userId(userId).name("Ancien").build());
        givenRunner(false, null);
        givenTree(3, false);

        assertThat(status().engine()).isEqualTo(AtelierEngine.HOSTED_SANDBOX);
    }

    /**
     * D-L4-1 : un runner éteint ne fait pas repartir le projet dans un bac à sable vide. Le moteur
     * reste celui que le projet déclare ; la déconnexion est un état de santé, rendu à part.
     */
    @Test
    void engineDoesNotFallBackWhenTheRunnerIsOffline() {
        givenWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        OffsetDateTime lastSeen = OffsetDateTime.now().minus(Duration.ofHours(3));
        givenRunner(false, lastSeen);

        EngineStatus status = status();

        assertThat(status.engine()).isEqualTo(AtelierEngine.LOCAL_MACHINE);
        assertThat(status.runnerConnected()).isFalse();
        assertThat(status.runnerLastSeenAt()).isEqualTo(lastSeen);
    }

    // ---------- Faut-il proposer le runner (D6) ----------

    @Test
    void firstArchiveProjectIsNeverAskedToInstallARunner() {
        givenWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.SANDBOX);
        givenRunner(false, null);
        givenTree(12, false);

        EngineStatus status = status();

        assertThat(status.recommendRunner()).isFalse();
        assertThat(status.recommendReason()).isNull();
    }

    @Test
    void gitProjectRecommendsTheRunner() {
        givenWorkspace(WorkspaceSource.GIT, WorkspaceExecutionTarget.SANDBOX);
        givenRunner(false, null);

        EngineStatus status = status();

        assertThat(status.recommendRunner()).isTrue();
        assertThat(status.recommendReason()).isEqualTo(RunnerRecommendation.GIT);
    }

    @Test
    void truncatedTreeRecommendsTheRunnerOnTheFileLimit() {
        givenWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.SANDBOX);
        givenRunner(false, null);
        givenTree(10, true);

        assertThat(status().recommendReason()).isEqualTo(RunnerRecommendation.FILE_LIMIT);
    }

    @Test
    void projectAtTheMountLimitRecommendsTheRunner() {
        givenWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.SANDBOX);
        givenRunner(false, null);
        givenTree(MOUNT_LIMIT, false);

        assertThat(status().recommendReason()).isEqualTo(RunnerRecommendation.FILE_LIMIT);
    }

    /** Un dépôt volumineux réunit les deux motifs : c'est celui qui se comprend seul qui gagne. */
    @Test
    void gitWinsOverTheFileLimit() {
        givenWorkspace(WorkspaceSource.GIT, WorkspaceExecutionTarget.SANDBOX);
        givenRunner(false, null);

        assertThat(status().recommendReason()).isEqualTo(RunnerRecommendation.GIT);
        verify(gitWorkspaceService, never()).tree(any(), any());
    }

    @Test
    void nothingIsRecommendedWhenARunnerIsAlreadyConnected() {
        givenWorkspace(WorkspaceSource.GIT, WorkspaceExecutionTarget.SANDBOX);
        givenRunner(true, OffsetDateTime.now());

        EngineStatus status = status();

        assertThat(status.recommendRunner()).isFalse();
        assertThat(status.recommendReason()).isNull();
    }

    @Test
    void nothingIsRecommendedToSomeoneAlreadyRunningOnTheirMachine() {
        givenWorkspace(WorkspaceSource.GIT, WorkspaceExecutionTarget.RUNNER);
        givenRunner(false, null);

        EngineStatus status = status();

        assertThat(status.recommendRunner()).isFalse();
        assertThat(status.recommendReason()).isNull();
        verify(gitWorkspaceService, never()).tree(any(), any());
    }
}
