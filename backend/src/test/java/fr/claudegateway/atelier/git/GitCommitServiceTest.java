package fr.claudegateway.atelier.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
import fr.claudegateway.git.GitCommitResult;
import fr.claudegateway.git.GitFileEdit;
import fr.claudegateway.git.GitHubClient;
import fr.claudegateway.git.GitPullRequest;
import fr.claudegateway.git.GitTokenMissingException;
import fr.claudegateway.git.GitTokenService;

/**
 * Publication des modifications faites par l'utilisateur (F-31 / SF-31-08) : jamais sur la branche
 * du projet, jamais sans jeton, et un commit unique portant tous les fichiers.
 */
@ExtendWith(MockitoExtension.class)
class GitCommitServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private GitTokenService gitTokenService;
    @Mock
    private GitHubClient gitHubClient;

    private GitCommitService service() {
        return new GitCommitService(workspaceService, gitTokenService, gitHubClient);
    }

    private Workspace gitWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE);
        workspace.setUserId(USER);
        workspace.setSource(WorkspaceSource.GIT);
        workspace.setGitOwner("octocat");
        workspace.setGitRepo("hello");
        workspace.setGitBranch("main");
        return workspace;
    }

    /** Le refus porte désormais sur la branche par défaut du dépôt (SF-31-10), lue auprès de GitHub. */
    private void withDefaultBranch(String defaultBranch) {
        when(gitHubClient.getRepository(anyString(), anyString(), anyString()))
                .thenReturn(new fr.claudegateway.git.GitHubRepository("octocat/hello", defaultBranch));
    }

    private static List<GitFileEdit> oneFile() {
        return List.of(new GitFileEdit("src/App.java", "class App {}"));
    }

    @Test
    void publishesASingleCommitOnTheRequestedBranch() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        withDefaultBranch("main");
        when(gitHubClient.commitFiles(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(new GitCommitResult("claude/edition", "abc123", true));
        when(gitHubClient.findOpenPullRequest(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        List<GitFileEdit> files = List.of(
                new GitFileEdit("a.txt", "un"), new GitFileEdit("b.txt", "deux"));
        GitCommitService.CommitPublication published =
                service().commit(USER, WORKSPACE, "claude/edition", "Mes modifications", files);

        // Un seul appel, portant les deux fichiers : le commit est atomique, pas un par fichier.
        ArgumentCaptor<List<GitFileEdit>> sent = ArgumentCaptor.captor();
        verify(gitHubClient).commitFiles(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), sent.capture());
        assertThat(sent.getValue()).hasSize(2);
        assertThat(published.result().commitSha()).isEqualTo("abc123");
        assertThat(published.compareUrl())
                .isEqualTo("https://github.com/octocat/hello/compare/main...claude/edition?expand=1");
        assertThat(published.pullRequest()).isNull();
    }

    @Test
    void refusesTheRepositoryDefaultBranch() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        withDefaultBranch("main");

        assertThatThrownBy(() -> service().commit(USER, WORKSPACE, "main", "Direct sur main", oneFile()))
                .isInstanceOf(GitDefaultBranchRefusedException.class);

        // Aucune écriture : le refus tombe avant le commit, seule la lecture du dépôt a eu lieu.
        verify(gitHubClient, never()).commitFiles(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    /**
     * Régression SF-31-10 : depuis que le projet peut suivre une branche de travail, publier
     * dessus doit être POSSIBLE. L'ancienne règle (« refuser la branche du projet ») l'interdisait.
     */
    @Test
    void allowsCommittingOnTheProjectBranchWhenItIsNotTheDefaultOne() {
        Workspace onWorkBranch = gitWorkspace();
        onWorkBranch.setGitBranch("claude/edition");
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(onWorkBranch);
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        withDefaultBranch("main");
        when(gitHubClient.commitFiles(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(new GitCommitResult("claude/edition", "abc", false));
        when(gitHubClient.findOpenPullRequest(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        GitCommitService.CommitPublication published =
                service().commit(USER, WORKSPACE, "claude/edition", "Suite", oneFile());

        assertThat(published.result().branch()).isEqualTo("claude/edition");
    }

    @Test
    void refusesAnArchiveWorkspace() {
        Workspace archive = new Workspace();
        archive.setId(WORKSPACE);
        archive.setUserId(USER);
        archive.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(archive);

        assertThatThrownBy(() -> service().commit(USER, WORKSPACE, "claude/x", "m", oneFile()))
                .isInstanceOf(GitWorkspaceRequiredException.class);
        verifyNoInteractions(gitHubClient);
    }

    @Test
    void refusesWhenNoTokenIsRegistered() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().commit(USER, WORKSPACE, "claude/x", "m", oneFile()))
                .isInstanceOf(GitTokenMissingException.class);
        verifyNoInteractions(gitHubClient);
    }

    @Test
    void reportsAnAlreadyOpenPullRequest() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        withDefaultBranch("main");
        when(gitHubClient.commitFiles(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(new GitCommitResult("claude/x", "abc123", false));
        when(gitHubClient.findOpenPullRequest(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new GitPullRequest(7, "https://github.com/octocat/hello/pull/7")));

        GitCommitService.CommitPublication published =
                service().commit(USER, WORKSPACE, "claude/x", "m", oneFile());

        assertThat(published.pullRequest().url()).endsWith("/pull/7");
        assertThat(published.result().branchCreated()).isFalse();
    }

    @Test
    void startsFromTheProjectBranch() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        withDefaultBranch("main");
        when(gitHubClient.commitFiles(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(new GitCommitResult("claude/x", "abc", true));
        when(gitHubClient.findOpenPullRequest(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        service().commit(USER, WORKSPACE, "claude/x", "m", oneFile());

        ArgumentCaptor<String> base = ArgumentCaptor.captor();
        verify(gitHubClient).commitFiles(anyString(), anyString(), anyString(), base.capture(),
                anyString(), anyString(), any());
        assertThat(base.getValue()).isEqualTo("main");
    }
}
