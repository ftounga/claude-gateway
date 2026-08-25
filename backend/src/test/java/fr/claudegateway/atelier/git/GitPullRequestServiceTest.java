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
 * Vérifie l'ouverture de la pull request (F-31 / SF-31-05) : la pull request est <b>constatée</b>
 * auprès de GitHub plutôt que déduite de ce que l'agent déclare, aucun secret ne descend dans la
 * sandbox, aucun tour n'est engagé quand la demande est refusable d'avance, et le tour se joue dans
 * la session existante — jamais dans une session neuve, qui repartirait d'un clone vierge.
 */
@ExtendWith(MockitoExtension.class)
class GitPullRequestServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final GitPullRequest OPENED =
            new GitPullRequest(42, "https://github.com/octocat/hello/pull/42");

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private AtelierSessionService sessionService;
    @Mock
    private GitTokenService gitTokenService;
    @Mock
    private GitHubClient gitHubClient;

    private GitPullRequestService service() {
        return new GitPullRequestService(workspaceService, sessionService, gitTokenService, gitHubClient,
                new GitProperties(null, null, null, null, null, null));
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

    /** Instruction réellement envoyée à la sandbox. */
    private String instructionSent() {
        ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
        verify(sessionService).runInExistingSession(eq(USER), eq(WORKSPACE), instruction.capture());
        return instruction.getValue();
    }

    @Test
    void returnsTheUrlAndNumberOfThePullRequestConstatedOnGitHub() {
        stubOwnedGitWorkspace();
        stubTokenAndRun("Pull request ouverte.");
        when(gitHubClient.findOpenPullRequest("github_pat_secret", "octocat", "hello", "feat/atelier"))
                .thenReturn(Optional.of(OPENED));

        PullRequestResponse response = service()
                .create(USER, WORKSPACE, "feat/atelier", "Corrige le bug", "Détail");

        assertThat(response.branch()).isEqualTo("feat/atelier");
        assertThat(response.created()).isTrue();
        assertThat(response.url()).isEqualTo("https://github.com/octocat/hello/pull/42");
        assertThat(response.number()).isEqualTo(42);
        assertThat(response.reply()).isEqualTo("Pull request ouverte.");
    }

    @Test
    void reportsAFailureInsteadOfTrustingTheAgent() {
        stubOwnedGitWorkspace();
        stubTokenAndRun("J'ai ouvert la pull request."); // déclaration non tenue
        when(gitHubClient.findOpenPullRequest(any(), any(), any(), any())).thenReturn(Optional.empty());

        PullRequestResponse response = service().create(USER, WORKSPACE, "feat/atelier", null, null);

        assertThat(response.created()).isFalse();
        assertThat(response.url()).isNull();
        assertThat(response.number()).isNull();
        // Le compte rendu reste : c'est là que se lit la cause (droits, outil absent, PR déjà ouverte).
        assertThat(response.reply()).isEqualTo("J'ai ouvert la pull request.");
    }

    @Test
    void neverOpensAPullRequestFromTheBaseBranch() {
        stubOwnedGitWorkspace();

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, "main", null, null))
                .isInstanceOf(InvalidGitBranchException.class);

        // Refus AVANT toute dépense : ni tour joué, ni appel GitHub, ni déchiffrement du jeton.
        verifyNoInteractions(sessionService, gitHubClient, gitTokenService);
    }

    @Test
    void refusesAnInvalidBranchNameBeforeSpendingAnything() {
        stubOwnedGitWorkspace();

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, "../evil", null, null))
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

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, "feat/x", null, null))
                .isInstanceOf(GitWorkspaceRequiredException.class);

        verifyNoInteractions(sessionService, gitHubClient, gitTokenService);
    }

    @Test
    void refusesWhenNoTokenIsRegistered() {
        stubOwnedGitWorkspace();
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, "feat/x", null, null))
                .isInstanceOf(GitTokenMissingException.class);

        // Sans jeton, la vérification d'après-coup serait impossible : on n'engage pas le tour.
        verifyNoInteractions(sessionService, gitHubClient);
    }

    @Test
    void neverOpensASessionForAPullRequest() {
        stubOwnedGitWorkspace();
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(sessionService.runInExistingSession(eq(USER), eq(WORKSPACE), anyString()))
                .thenThrow(new NoActiveSessionException("aucune session"));

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, "feat/x", null, null))
                .isInstanceOf(NoActiveSessionException.class);

        verifyNoInteractions(gitHubClient);
    }

    @Test
    void doesNotClaimSuccessWhenGitHubIsUnreachable() {
        stubOwnedGitWorkspace();
        stubTokenAndRun("ok");
        when(gitHubClient.findOpenPullRequest(any(), any(), any(), any()))
                .thenThrow(new GitHubUnavailableException("panne"));

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, "feat/x", null, null))
                .isInstanceOf(GitHubUnavailableException.class);
    }

    @Test
    void theInstructionNamesTheMcpToolAndCarriesNoSecret() {
        stubOwnedGitWorkspace();
        stubTokenAndRun("ok");
        when(gitHubClient.findOpenPullRequest(any(), any(), any(), any())).thenReturn(Optional.of(OPENED));

        service().create(USER, WORKSPACE, "feat/atelier", "Mon titre", "Mon corps");

        String instruction = instructionSent();
        // L'outil est nommé : sans cela l'agent tenterait un curl depuis une sandbox sans jeton.
        assertThat(instruction).contains("create_pull_request").contains("github");
        assertThat(instruction).contains("octocat").contains("hello")
                .contains("feat/atelier").contains("main")
                .contains("Mon titre").contains("Mon corps");
        // Le jeton vit dans le vault, chez le fournisseur : il ne descend jamais dans le conteneur.
        assertThat(instruction).doesNotContain("github_pat_secret");
    }

    @Test
    void fallsBackToADefaultTitleAndBodyWhenNoneIsGiven() {
        stubOwnedGitWorkspace();
        stubTokenAndRun("ok");
        when(gitHubClient.findOpenPullRequest(any(), any(), any(), any())).thenReturn(Optional.of(OPENED));

        service().create(USER, WORKSPACE, "feat/atelier", "   ", "");

        assertThat(instructionSent())
                .contains("Travaux de l'Atelier : feat/atelier")
                .contains("Pull request ouverte depuis l'Atelier Claude Gateway.");
    }

    @Test
    void boundsAnOversizedTitleAndBodyBeforeSpendingSandboxTime() {
        stubOwnedGitWorkspace();
        stubTokenAndRun("ok");
        when(gitHubClient.findOpenPullRequest(any(), any(), any(), any())).thenReturn(Optional.of(OPENED));

        service().create(USER, WORKSPACE, "feat/atelier", "T".repeat(500), "B".repeat(10_000));

        String instruction = instructionSent();
        assertThat(instruction).doesNotContain("T".repeat(201));
        assertThat(instruction).doesNotContain("B".repeat(4_001));
    }

    @Test
    void refusesAWorkspaceOfAnotherUserBeforeAnythingElse() {
        when(workspaceService.requireOwned(USER, WORKSPACE))
                .thenThrow(new WorkspaceNotFoundException("Projet introuvable"));

        assertThatThrownBy(() -> service().create(USER, WORKSPACE, "feat/x", null, null))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verifyNoInteractions(sessionService, gitHubClient, gitTokenService);
    }
}
