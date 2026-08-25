package fr.claudegateway.atelier.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.git.GitHubClient;
import fr.claudegateway.git.GitHubRepository;
import fr.claudegateway.git.GitHubUnavailableException;
import fr.claudegateway.git.GitTokenMissingException;
import fr.claudegateway.git.GitTokenService;
import fr.claudegateway.git.InvalidGitRepositoryException;

/**
 * Vérifie l'ouverture d'un projet sur un dépôt (F-31 / SF-31-02) : ordre des contrôles (URL, puis
 * jeton, puis accès au dépôt, puis seulement écriture), résolution de la branche par défaut, et
 * absence totale d'effet de bord quand une étape échoue.
 */
@ExtendWith(MockitoExtension.class)
class GitWorkspaceServiceTest {

    private static final UUID USER = UUID.randomUUID();

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private GitTokenService gitTokenService;
    @Mock
    private GitHubClient gitHubClient;

    private GitWorkspaceService service() {
        return new GitWorkspaceService(workspaceService, gitTokenService, gitHubClient);
    }

    private Workspace created() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setUserId(USER);
        workspace.setSource(WorkspaceSource.GIT);
        return workspace;
    }

    @Test
    void createsGitWorkspaceOnRepositoryDefaultBranch() {
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(gitHubClient.getRepository("github_pat_secret", "octocat", "hello"))
                .thenReturn(new GitHubRepository("octocat/hello", "main"));
        when(workspaceService.createFromGit(eq(USER), any(), any(), any(), any(), any()))
                .thenReturn(created());

        service().create(USER, "https://github.com/octocat/hello", null, "Mon projet");

        verify(workspaceService).createFromGit(USER, "Mon projet",
                "https://github.com/octocat/hello", "octocat", "hello", "main");
    }

    @Test
    void keepsTheBranchRequestedByTheUser() {
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(gitHubClient.getRepository(any(), any(), any()))
                .thenReturn(new GitHubRepository("octocat/hello", "main"));
        when(workspaceService.createFromGit(eq(USER), any(), any(), any(), any(), any()))
                .thenReturn(created());

        service().create(USER, "https://github.com/octocat/hello", "feat/atelier", null);

        verify(workspaceService).createFromGit(USER, null, "https://github.com/octocat/hello",
                "octocat", "hello", "feat/atelier");
    }

    @Test
    void refusesInvalidUrlWithoutAnyNetworkCallOrWrite() {
        assertThatThrownBy(() -> service().create(USER, "https://gitlab.com/octocat/hello", null, null))
                .isInstanceOf(InvalidGitRepositoryException.class);

        verifyNoInteractions(gitTokenService, gitHubClient, workspaceService);
    }

    @Test
    void refusesWhenNoTokenIsRegisteredWithoutCallingGitHub() {
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().create(USER, "https://github.com/octocat/hello", null, null))
                .isInstanceOf(GitTokenMissingException.class);

        verifyNoInteractions(gitHubClient, workspaceService);
    }

    @Test
    void doesNotCreateAnythingWhenRepositoryIsOutOfReach() {
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(gitHubClient.getRepository(any(), any(), any()))
                .thenThrow(new InvalidGitRepositoryException("introuvable"));

        assertThatThrownBy(() -> service().create(USER, "https://github.com/octocat/hello", null, null))
                .isInstanceOf(InvalidGitRepositoryException.class);

        verifyNoInteractions(workspaceService);
    }

    @Test
    void doesNotCreateAnythingWhenGitHubIsUnavailable() {
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(gitHubClient.getRepository(any(), any(), any()))
                .thenThrow(new GitHubUnavailableException("indisponible"));

        assertThatThrownBy(() -> service().create(USER, "https://github.com/octocat/hello", null, null))
                .isInstanceOf(GitHubUnavailableException.class);

        verifyNoInteractions(workspaceService);
    }

    @Test
    void returnsTheCreatedWorkspace() {
        Workspace workspace = created();
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(gitHubClient.getRepository(any(), any(), any()))
                .thenReturn(new GitHubRepository("octocat/hello", "main"));
        when(workspaceService.createFromGit(eq(USER), any(), any(), any(), any(), any()))
                .thenReturn(workspace);

        assertThat(service().create(USER, "https://github.com/octocat/hello", null, null))
                .isSameAs(workspace);
    }
}
