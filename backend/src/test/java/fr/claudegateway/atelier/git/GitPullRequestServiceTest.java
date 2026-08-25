package fr.claudegateway.atelier.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.atelier.agent.AtelierSessionResult;
import fr.claudegateway.atelier.agent.AtelierSessionService;
import fr.claudegateway.atelier.agent.NoActiveSessionException;
import fr.claudegateway.atelier.git.dto.PullRequestResponse;
import fr.claudegateway.git.GitHubClient;
import fr.claudegateway.git.GitHubUnavailableException;
import fr.claudegateway.git.GitProperties;
import fr.claudegateway.git.GitPullRequest;
import fr.claudegateway.git.GitTokenMissingException;
import fr.claudegateway.git.GitTokenService;
import fr.claudegateway.git.InvalidGitBranchException;

/**
 * Vérifie l'ouverture de pull request (F-31 / SF-31-05) : jamais depuis la branche de base, jamais de
 * session ouverte pour l'occasion, résultat <b>constaté</b> auprès de GitHub plutôt que déduit de ce
 * que l'agent déclare, et aucun secret dans l'instruction envoyée à la sandbox.
 */
@ExtendWith(MockitoExtension.class)
class GitPullRequestServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final String BRANCH = "claude/atelier-20260825-1200";

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private AtelierSessionService sessionService;
    @Mock
    private GitTokenService gitTokenService;
    @Mock
    private GitHubClient gitHubClient;

    private final GitProperties properties = new GitProperties(null, null, null, null, null, null);

    private GitPullRequestService service() {
        return new GitPullRequestService(workspaceService, sessionService, gitTokenService, gitHubClient,
                properties);
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

    private void stubTokenAndRun(String reply) {
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(sessionService.runInExistingSession(eq(USER), eq(WORKSPACE), anyString()))
                .thenReturn(new AtelierSessionResult(reply, java.util.List.of()));
    }

    @Test
    void anOpenedPullRequestIsReportedWithItsUrlAndNumber() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        stubTokenAndRun("Pull request créée.");
        when(gitHubClient.findOpenPullRequest("github_pat_secret", "octocat", "hello", BRANCH))
                .thenReturn(Optional.of(new GitPullRequest(7, "https://github.com/octocat/hello/pull/7")));

        PullRequestResponse response = service().create(USER, WORKSPACE, BRANCH, null, null);

        assertThat(response.created()).isTrue();
        assertThat(response.number()).isEqualTo(7);
        assertThat(response.url()).isEqualTo("https://github.com/octocat/hello/pull/7");
        assertThat(response.branch()).isEqualTo(BRANCH);
        assertThat(response.reply()).isEqualTo("Pull request créée.");
    }

    @Test
    void anAgentClaimingSuccessWithoutAPullRequestIsNotBelieved() {
        // Un agent peut annoncer une création qui n'a pas eu lieu : jeton sans droit d'écriture, outil
        // MCP indisponible, pull request déjà ouverte. Fabriquer une URL mènerait à une page absente.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        stubTokenAndRun("J'ai ouvert la pull request.");
        when(gitHubClient.findOpenPullRequest(any(), any(), any(), any())).thenReturn(Optional.empty());

        PullRequestResponse response = service().create(USER, WORKSPACE, BRANCH, null, null);

        assertThat(response.created()).isFalse();
        assertThat(response.url()).isNull();
        assertThat(response.number()).isNull();
        assertThat(response.reply()).isEqualTo("J'ai ouvert la pull request.");
    }

    @Test
    void theInstructionNamesTheMcpToolAndCarriesNoSecret() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        stubTokenAndRun("ok");
        when(gitHubClient.findOpenPullRequest(any(), any(), any(), any())).thenReturn(Optional.empty());

        service().create(USER, WORKSPACE, BRANCH, "Corrige le bug", "Détail");

        ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
        verify(sessionService).runInExistingSession(eq(USER), eq(WORKSPACE), instruction.capture());
        assertThat(instruction.getValue())
                .contains("create_pull_request")
                .contains("octocat")
                .contains("hello")
                .contains(BRANCH)
                .contains("main")
                .contains("Corrige le bug")
                .contains("Détail")
                .doesNotContain("github_pat_secret");
    }

    @Test
    void aWorkspaceOfAnotherUserIsNotFoundAndNoTurnIsPlayed() {
        when(workspaceService.requireOwned(USER, WORKSPACE))
                .thenThrow(new WorkspaceNotFoundException("Projet introuvable"));

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, BRANCH, null, null))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verifyNoInteractions(sessionService, gitTokenService, gitHubClient);
    }

    @Test
    void anArchiveProjectHasNoPullRequestToOpen() {
        Workspace archive = new Workspace();
        archive.setId(WORKSPACE);
        archive.setUserId(USER);
        archive.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(archive);

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, BRANCH, null, null))
                .isInstanceOf(GitWorkspaceRequiredException.class);

        verifyNoInteractions(sessionService, gitTokenService, gitHubClient);
    }

    @Test
    void anInvalidBranchNeverReachesTheSandbox() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, "branche invalide !", null, null))
                .isInstanceOf(InvalidGitBranchException.class);

        verifyNoInteractions(sessionService, gitTokenService, gitHubClient);
    }

    @Test
    void aMissingBranchIsRefusedRatherThanGuessed() {
        // Deviner « la dernière branche poussée » ouvrirait la mauvaise pull request le jour où
        // l'utilisateur en a publié deux.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, null, null, null))
                .isInstanceOf(InvalidGitBranchException.class);

        verifyNoInteractions(sessionService, gitTokenService, gitHubClient);
    }

    @Test
    void theBaseBranchCannotBeItsOwnPullRequestHead() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, "main", null, null))
                .isInstanceOf(InvalidGitBranchException.class)
                .hasMessageContaining("main");

        verifyNoInteractions(sessionService, gitTokenService, gitHubClient);
    }

    @Test
    void withoutATokenNothingIsAttempted() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, BRANCH, null, null))
                .isInstanceOf(GitTokenMissingException.class);

        verifyNoInteractions(sessionService, gitHubClient);
    }

    @Test
    void withoutAnOpenSessionNoNewOneIsCreated() {
        // Une session neuve repartirait d'un clone vierge : elle n'aurait rien à proposer en PR.
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(sessionService.runInExistingSession(eq(USER), eq(WORKSPACE), anyString()))
                .thenThrow(new NoActiveSessionException("aucune session"));

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, BRANCH, null, null))
                .isInstanceOf(NoActiveSessionException.class);

        verifyNoInteractions(gitHubClient);
    }

    @Test
    void anUnreachableGitHubNeverPretendsThePullRequestExists() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        stubTokenAndRun("ok");
        when(gitHubClient.findOpenPullRequest(any(), any(), any(), any()))
                .thenThrow(new GitHubUnavailableException("panne"));

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, BRANCH, null, null))
                .isInstanceOf(GitHubUnavailableException.class);
    }

    @Test
    void aBlankTitleFallsBackToATitleDerivedFromTheBranch() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        stubTokenAndRun("ok");
        when(gitHubClient.findOpenPullRequest(any(), any(), any(), any())).thenReturn(Optional.empty());

        service().create(USER, WORKSPACE, BRANCH, "   ", "  ");

        ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
        verify(sessionService).runInExistingSession(eq(USER), eq(WORKSPACE), instruction.capture());
        assertThat(instruction.getValue())
                .contains("Travaux de l'Atelier : " + BRANCH)
                .contains("Pull request ouverte depuis l'Atelier Claude Gateway.");
    }
}
