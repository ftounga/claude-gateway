package fr.claudegateway.atelier.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.git.GitHubClient;
import fr.claudegateway.git.GitHubRepository;
import fr.claudegateway.git.GitTokenService;

/**
 * Choix et création de branche (F-31 / SF-31-10) : une branche inexistante ne doit jamais devenir
 * celle du projet, et une branche déjà présente n'est jamais réécrite.
 */
@ExtendWith(MockitoExtension.class)
class GitBranchServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private WorkspaceRepository workspaceRepository;
    @Mock
    private GitTokenService gitTokenService;
    @Mock
    private GitHubClient gitHubClient;

    private GitBranchService service() {
        return new GitBranchService(workspaceService, workspaceRepository, gitTokenService, gitHubClient);
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

    private void withToken() {
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
    }

    @Test
    void switchesToAnExistingBranch() {
        Workspace workspace = gitWorkspace();
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        withToken();
        when(gitHubClient.branchExists(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));

        Workspace saved = service().switchTo(USER, WORKSPACE, "claude/edition");

        assertThat(saved.getGitBranch()).isEqualTo("claude/edition");
    }

    @Test
    void refusesAnUnknownBranchAndLeavesTheProjectUntouched() {
        Workspace workspace = gitWorkspace();
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        withToken();
        when(gitHubClient.branchExists(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service().switchTo(USER, WORKSPACE, "fantome"))
                .isInstanceOf(GitBranchUnknownException.class);

        assertThat(workspace.getGitBranch()).as("le projet reste sur sa branche").isEqualTo("main");
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void createsABranchFromTheCurrentOneAndSwitchesToIt() {
        Workspace workspace = gitWorkspace();
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(workspace);
        withToken();
        when(gitHubClient.branchExists(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));

        Workspace saved = service().createAndSwitch(USER, WORKSPACE, "claude/nouvelle");

        verify(gitHubClient).createBranch(anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(saved.getGitBranch()).isEqualTo("claude/nouvelle");
    }

    @Test
    void refusesToCreateABranchThatAlreadyExists() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        withToken();
        when(gitHubClient.branchExists(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service().createAndSwitch(USER, WORKSPACE, "main"))
                .isInstanceOf(GitBranchExistsException.class);

        verify(gitHubClient, never()).createBranch(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void listsBranchesWithTheCurrentAndDefaultOnes() {
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(gitWorkspace());
        withToken();
        when(gitHubClient.listBranches(anyString(), anyString(), anyString()))
                .thenReturn(List.of("main", "claude/edition"));
        when(gitHubClient.getRepository(anyString(), anyString(), anyString()))
                .thenReturn(new GitHubRepository("octocat/hello", "main"));

        GitBranchService.Branches branches = service().list(USER, WORKSPACE);

        assertThat(branches.branches()).containsExactly("main", "claude/edition");
        assertThat(branches.current()).isEqualTo("main");
        assertThat(branches.defaultBranch()).isEqualTo("main");
    }

    @Test
    void refusesAnArchiveWorkspace() {
        Workspace archive = new Workspace();
        archive.setId(WORKSPACE);
        archive.setUserId(USER);
        archive.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(USER, WORKSPACE)).thenReturn(archive);

        assertThatThrownBy(() -> service().switchTo(USER, WORKSPACE, "x"))
                .isInstanceOf(GitWorkspaceRequiredException.class);
    }
}
