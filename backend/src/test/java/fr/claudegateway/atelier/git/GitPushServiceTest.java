package fr.claudegateway.atelier.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.atelier.agent.AtelierSessionResult;
import fr.claudegateway.atelier.agent.AtelierSessionService;
import fr.claudegateway.atelier.agent.NoActiveSessionException;
import fr.claudegateway.atelier.git.dto.GitPushResponse;
import fr.claudegateway.git.GitHubClient;
import fr.claudegateway.git.GitHubUnavailableException;
import fr.claudegateway.git.GitTokenMissingException;
import fr.claudegateway.git.GitTokenService;
import fr.claudegateway.git.InvalidGitBranchException;

/**
 * Vérifie la publication sur branche (F-31 / SF-31-04) : jamais sur la branche de base, jamais de
 * session ouverte pour l'occasion, résultat <b>constaté</b> auprès de GitHub plutôt que déduit de ce
 * que l'agent déclare, et aucun secret dans l'instruction envoyée à la sandbox.
 */
@ExtendWith(MockitoExtension.class)
class GitPushServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private GitWorkspaceService gitWorkspaceService;
    @Mock
    private AtelierSessionService sessionService;
    @Mock
    private GitTokenService gitTokenService;
    @Mock
    private GitHubClient gitHubClient;

    private GitPushService service() {
        return new GitPushService(workspaceService, gitWorkspaceService, sessionService, gitTokenService,
                gitHubClient);
    }

    private Workspace gitWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE);
        workspace.setUserId(USER);
        workspace.setSource(WorkspaceSource.GIT);
        workspace.setGitOwner("octocat");
        workspace.setGitRepo("hello");
        workspace.setGitBranch("main");
        workspace.setAgentSessionId("sess_1");
        return workspace;
    }

    private void stubOwnedGitWorkspace() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
    }

    private void stubTokenAndRun(String reply) {
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(sessionService.runInExistingSession(eq(USER), eq(WORKSPACE), anyString()))
                .thenReturn(new AtelierSessionResult(reply, List.of(), 0L, 0L, 0L));
    }

    @Test
    void publishesOnTheRequestedBranchAndReturnsTheCompareLink() {
        stubOwnedGitWorkspace();
        stubTokenAndRun("Branche poussée.");
        when(gitHubClient.branchExists("github_pat_secret", "octocat", "hello", "feat/atelier"))
                .thenReturn(true);

        GitPushResponse response = service().push(USER, WORKSPACE, "feat/atelier", "Corrige le bug");

        assertThat(response.branch()).isEqualTo("feat/atelier");
        assertThat(response.pushed()).isTrue();
        assertThat(response.compareUrl())
                .isEqualTo("https://github.com/octocat/hello/compare/main...feat/atelier?expand=1");
        assertThat(response.reply()).isEqualTo("Branche poussée.");
    }

    @Test
    void generatesADedicatedBranchNameWhenNoneIsGiven() {
        stubOwnedGitWorkspace();
        stubTokenAndRun("ok");
        when(gitHubClient.branchExists(any(), any(), any(), any())).thenReturn(true);

        GitPushResponse response = service().push(USER, WORKSPACE, null, null);

        assertThat(response.branch()).startsWith("claude/atelier-");
    }

    @Test
    void neverPublishesOnTheBaseBranch() {
        stubOwnedGitWorkspace();

        assertThatThrownBy(() -> service().push(USER, WORKSPACE, "main", null))
                .isInstanceOf(InvalidGitBranchException.class);

        // Refus AVANT toute dépense : ni session, ni appel GitHub.
        verifyNoInteractions(sessionService, gitHubClient, gitTokenService);
    }

    @Test
    void refusesAnInvalidBranchNameBeforeSpendingAnything() {
        stubOwnedGitWorkspace();

        assertThatThrownBy(() -> service().push(USER, WORKSPACE, "../evil", null))
                .isInstanceOf(InvalidGitBranchException.class);

        verifyNoInteractions(sessionService, gitHubClient, gitTokenService);
    }

    @Test
    void refusesAnArchiveWorkspace() {
        Workspace archive = new Workspace();
        archive.setId(WORKSPACE);
        archive.setUserId(USER);
        archive.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(archive);

        assertThatThrownBy(() -> service().push(USER, WORKSPACE, "feat/x", null))
                .isInstanceOf(GitWorkspaceRequiredException.class);

        verifyNoInteractions(sessionService, gitHubClient, gitTokenService);
    }

    @Test
    void refusesWhenNoTokenIsRegistered() {
        stubOwnedGitWorkspace();
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().push(USER, WORKSPACE, "feat/x", null))
                .isInstanceOf(GitTokenMissingException.class);

        // Sans jeton, la vérification serait impossible : on n'engage pas le tour.
        verifyNoInteractions(sessionService, gitHubClient);
    }

    @Test
    void neverOpensASessionForAPush() {
        stubOwnedGitWorkspace();
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(sessionService.runInExistingSession(eq(USER), eq(WORKSPACE), anyString()))
                .thenThrow(new NoActiveSessionException("aucune session"));

        assertThatThrownBy(() -> service().push(USER, WORKSPACE, "feat/x", null))
                .isInstanceOf(NoActiveSessionException.class);

        verifyNoInteractions(gitHubClient);
    }

    @Test
    void reportsAFailedPushInsteadOfTrustingTheAgent() {
        stubOwnedGitWorkspace();
        stubTokenAndRun("J'ai poussé la branche."); // déclaration non vérifiée
        when(gitHubClient.branchExists(any(), any(), any(), any())).thenReturn(false);

        GitPushResponse response = service().push(USER, WORKSPACE, "feat/x", null);

        assertThat(response.pushed()).isFalse();
        assertThat(response.compareUrl()).isNull();
        // Le compte rendu est conservé : c'est là que se lit la cause.
        assertThat(response.reply()).isEqualTo("J'ai poussé la branche.");
    }

    @Test
    void doesNotClaimSuccessWhenGitHubIsUnreachable() {
        stubOwnedGitWorkspace();
        stubTokenAndRun("ok");
        when(gitHubClient.branchExists(any(), any(), any(), any()))
                .thenThrow(new GitHubUnavailableException("panne"));

        assertThatThrownBy(() -> service().push(USER, WORKSPACE, "feat/x", null))
                .isInstanceOf(GitHubUnavailableException.class);
    }

    @Test
    void theInstructionSentToTheSandboxCarriesNoSecret() {
        stubOwnedGitWorkspace();
        stubTokenAndRun("ok");
        when(gitHubClient.branchExists(any(), any(), any(), any())).thenReturn(true);

        service().push(USER, WORKSPACE, "feat/x", "Corrige le bug");

        ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
        verify(sessionService).runInExistingSession(eq(USER), eq(WORKSPACE), instruction.capture());
        assertThat(instruction.getValue())
                .doesNotContain("github_pat_secret")
                .contains("feat/x")
                .contains("Corrige le bug")
                // La branche de base est nommée pour être explicitement exclue.
                .contains("main");
    }
}
