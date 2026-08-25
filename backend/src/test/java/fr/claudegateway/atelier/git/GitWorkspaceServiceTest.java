package fr.claudegateway.atelier.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.git.GitHubClient;
import fr.claudegateway.git.GitHubRepository;
import fr.claudegateway.git.GitHubUnavailableException;
import fr.claudegateway.git.GitTokenMissingException;
import fr.claudegateway.git.GitTokenService;
import fr.claudegateway.git.GitTreeListing;
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
        return new GitWorkspaceService(workspaceService, gitTokenService, gitHubClient,
                new fr.claudegateway.git.GitProperties(null, null, null, null));
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

    // -------------------------------- F-31 / SF-31-03 : arborescence et lecture

    private Workspace gitWorkspace() {
        Workspace workspace = created();
        workspace.setGitOwner("octocat");
        workspace.setGitRepo("hello");
        workspace.setGitBranch("main");
        return workspace;
    }

    private Workspace archiveWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setUserId(USER);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        return workspace;
    }

    @Test
    void mergesTheBranchAndTheLocallyRewrittenFiles() {
        Workspace workspace = gitWorkspace();
        when(workspaceService.tree(USER, workspace.getId())).thenReturn(List.of("src/App.java", "note.md"));
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(gitHubClient.listTree("github_pat_secret", "octocat", "hello", "main", 5000))
                .thenReturn(new GitTreeListing(List.of("README.md", "src/App.java"), false));

        GitWorkspaceService.WorkspaceContent content = service().tree(USER, workspace);

        // Union triée, sans doublon : `src/App.java` est présent des deux côtés.
        assertThat(content.files()).containsExactly("README.md", "note.md", "src/App.java");
        assertThat(content.truncated()).isFalse();
    }

    @Test
    void reportsATruncatedTreeInsteadOfPretendingItIsComplete() {
        Workspace workspace = gitWorkspace();
        when(workspaceService.tree(USER, workspace.getId())).thenReturn(List.of());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(gitHubClient.listTree(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new GitTreeListing(List.of("a.txt"), true));

        assertThat(service().tree(USER, workspace).truncated()).isTrue();
    }

    @Test
    void anArchiveWorkspaceNeverCallsGitHub() {
        Workspace workspace = archiveWorkspace();
        when(workspaceService.tree(USER, workspace.getId())).thenReturn(List.of("a.txt"));

        GitWorkspaceService.WorkspaceContent content = service().tree(USER, workspace);

        assertThat(content.files()).containsExactly("a.txt");
        assertThat(content.truncated()).isFalse();
        verifyNoInteractions(gitHubClient, gitTokenService);
    }

    @Test
    void theLocalVersionOfAFileWinsOverTheBranch() {
        Workspace workspace = gitWorkspace();
        when(workspaceService.readFile(USER, workspace.getId(), "src/App.java"))
                .thenReturn("version modifiée par la session");

        assertThat(service().readFile(USER, workspace, "src/App.java"))
                .isEqualTo("version modifiée par la session");

        verifyNoInteractions(gitHubClient);
    }

    @Test
    void aFileUntouchedBySessionIsReadOnTheBranch() {
        Workspace workspace = gitWorkspace();
        when(workspaceService.readFile(USER, workspace.getId(), "README.md"))
                .thenThrow(new WorkspaceNotFoundException("absent"));
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(gitHubClient.readFile("github_pat_secret", "octocat", "hello", "main", "README.md", 1_048_576L))
                .thenReturn("contenu de la branche");

        assertThat(service().readFile(USER, workspace, "README.md")).isEqualTo("contenu de la branche");
    }

    @Test
    void readingAGitWorkspaceWithoutTokenFails() {
        Workspace workspace = gitWorkspace();
        when(workspaceService.tree(USER, workspace.getId())).thenReturn(List.of());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().tree(USER, workspace))
                .isInstanceOf(GitTokenMissingException.class);

        verifyNoInteractions(gitHubClient);
    }

    @Test
    void writingIsRefusedOnAGitWorkspaceAndAllowedOnAnArchive() {
        assertThatThrownBy(() -> service().requireWritable(gitWorkspace()))
                .isInstanceOf(GitWorkspaceReadOnlyException.class);

        service().requireWritable(archiveWorkspace()); // aucune exception
    }

    @Test
    void assistantModeIsRefusedOnAGitWorkspaceAndAllowedOnAnArchive() {
        assertThatThrownBy(() -> service().requireArchiveChatMode(gitWorkspace()))
                .isInstanceOf(GitWorkspaceModeException.class);

        service().requireArchiveChatMode(archiveWorkspace()); // aucune exception
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
