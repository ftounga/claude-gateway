package fr.claudegateway.atelier.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.git.StubGitHubClient;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de l'ouverture d'un projet sur dépôt Git (F-31 / SF-31-02) :
 * {@code POST /api/workspaces/git}, gating Gold, authentification, cas d'erreur et isolation
 * {@code user_id}. GitHub est remplacé par un stub — aucun appel réseau.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GitWorkspaceApiIntegrationTest {

    @TestConfiguration
    static class StubGitHubConfig {
        @Bean
        @Primary
        StubGitHubClient stubGitHubClient() {
            return new StubGitHubClient();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private StubGitHubClient stubGitHubClient;

    private User alice;
    private String aliceToken;
    private String bobToken;
    private String plainToken;

    @BeforeEach
    void setUp() throws Exception {
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        stubGitHubClient.reset();

        alice = user("alice-git@ex.com");
        provisionGold(alice);
        aliceToken = jwtService.generateToken(alice);

        User bob = user("bob-git@ex.com");
        provisionGold(bob);
        bobToken = jwtService.generateToken(bob);

        // Utilisateur sans offre Gold : sert à vérifier que le gating de l'Atelier couvre l'endpoint.
        User plain = user("plain-git@ex.com");
        plainToken = jwtService.generateToken(plain);

        registerToken(aliceToken, "github_pat_alice");
        registerToken(bobToken, "github_pat_bob");
    }

    private User user(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    private void provisionGold(User user) {
        subscriptionRepository.save(Subscription.builder()
                .userId(user.getId())
                .planCode(PlanCode.GOLD)
                .status(SubscriptionStatus.ACTIVE)
                .build());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    /** Enregistre un jeton GitHub pour l'utilisateur, par l'API réelle (chiffrement compris). */
    private void registerToken(String jwt, String pat) throws Exception {
        mockMvc.perform(post("/api/user/git-token").contextPath("/api")
                        .header("Authorization", bearer(jwt))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + pat + "\"}"))
                .andExpect(status().isOk());
    }

    private String openRepository(String jwt, String body) throws Exception {
        String response = mockMvc.perform(post("/api/workspaces/git").contextPath("/api")
                        .header("Authorization", bearer(jwt))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    // ---------- SF-31-08 : publier ses propres modifications ----------

    private String commitBody(String branch, String message, String path, String content) {
        return "{\"branch\":\"" + branch + "\",\"message\":\"" + message
                + "\",\"files\":[{\"path\":\"" + path + "\",\"content\":\"" + content + "\"}]}";
    }

    @Test
    void publishesTheUserOwnEditsOnADedicatedBranch() throws Exception {
        registerToken(aliceToken, "github_pat_alice");
        String workspaceId = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/git/commit").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commitBody("claude/edition", "Mes modifications", "README.md", "bonjour")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").value("claude/edition"))
                .andExpect(jsonPath("$.commitSha").isNotEmpty())
                .andExpect(jsonPath("$.compareUrl").value(org.hamcrest.Matchers.containsString("compare/")));

        assertThat(stubGitHubClient.lastCommitBranch).isEqualTo("claude/edition");
        assertThat(stubGitHubClient.lastCommitFiles).hasSize(1);
    }

    @Test
    void refusesACommitOnTheProjectBranch() throws Exception {
        registerToken(aliceToken, "github_pat_alice");
        String workspaceId = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");
        int before = stubGitHubClient.commitCalls;

        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/git/commit").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commitBody(stubGitHubClient.defaultBranch, "Direct", "a.txt", "x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("git_default_branch_refused"));

        assertThat(stubGitHubClient.commitCalls).as("aucune écriture tentée").isEqualTo(before);
    }

    @Test
    void refusesACommitOnAWorkspaceOfAnotherUser() throws Exception {
        registerToken(aliceToken, "github_pat_alice");
        String workspaceId = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/git/commit").contextPath("/api")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commitBody("claude/x", "m", "a.txt", "x")))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesACommitWithoutAnyFile() throws Exception {
        registerToken(aliceToken, "github_pat_alice");
        String workspaceId = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/git/commit").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"claude/x\",\"message\":\"m\",\"files\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesACommitWithoutJwt() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + java.util.UUID.randomUUID() + "/git/commit")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commitBody("claude/x", "m", "a.txt", "x")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void opensAProjectOnTheRepositoryDefaultBranch() throws Exception {
        stubGitHubClient.defaultBranch = "develop";

        String id = openRepository(aliceToken,
                "{\"repoUrl\":\"https://github.com/octocat/hello\",\"name\":\"Mon dépôt\"}");

        Workspace workspace = workspaceRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(workspace.getUserId()).isEqualTo(alice.getId());
        assertThat(workspace.getSource()).isEqualTo(WorkspaceSource.GIT);
        assertThat(workspace.getGitRepoUrl()).isEqualTo("https://github.com/octocat/hello");
        assertThat(workspace.getGitOwner()).isEqualTo("octocat");
        assertThat(workspace.getGitRepo()).isEqualTo("hello");
        assertThat(workspace.getGitBranch()).isEqualTo("develop");
        // Aucun fichier n'est copié : le dépôt est cloné par le fournisseur à l'ouverture de session.
        assertThat(workspace.getName()).isEqualTo("Mon dépôt");
    }

    @Test
    void exposesTheSourceAndRepositoryInTheApi() throws Exception {
        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(get("/api/workspaces/" + id).contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", is("GIT")))
                .andExpect(jsonPath("$.gitRepo", is("octocat/hello")))
                .andExpect(jsonPath("$.gitBranch", is("main")))
                .andExpect(jsonPath("$.gitRepoUrl", is("https://github.com/octocat/hello")))
                .andExpect(jsonPath("$.name", is("hello")));

        mockMvc.perform(get("/api/workspaces").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].source", is("GIT")))
                .andExpect(jsonPath("$[0].gitRepo", is("octocat/hello")));
    }

    @Test
    void neverReturnsTheAccessTokenInAnyResponse() throws Exception {
        String response = mockMvc.perform(post("/api/workspaces/git").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://github.com/octocat/hello\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("github_pat_alice");
    }

    @Test
    void usesTheTokenOfTheRequestingUser() throws Exception {
        openRepository(bobToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        // Le jeton employé est celui de Bob : jamais celui d'un autre utilisateur.
        assertThat(stubGitHubClient.lastToken).isEqualTo("github_pat_bob");
        assertThat(stubGitHubClient.lastRepository).isEqualTo("octocat/hello");
    }

    @Test
    void refusesARepositoryOpenedByAnotherUser() throws Exception {
        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(get("/api/workspaces/" + id).contextPath("/api")
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesWhenNoGitHubTokenIsRegistered() throws Exception {
        mockMvc.perform(post("/api/user/git-token").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/user/git-token").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/workspaces/git").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://github.com/octocat/hello\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("git_token_missing")));

        assertThat(workspaceRepository.count()).isZero();
    }

    @Test
    void refusesANonGitHubUrl() throws Exception {
        mockMvc.perform(post("/api/workspaces/git").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://gitlab.com/octocat/hello\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_git_repository")));

        assertThat(workspaceRepository.count()).isZero();
    }

    @Test
    void refusesAnInvalidBranch() throws Exception {
        mockMvc.perform(post("/api/workspaces/git").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://github.com/octocat/hello\",\"branch\":\"-force\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_git_branch")));

        assertThat(workspaceRepository.count()).isZero();
    }

    @Test
    void refusesARepositoryOutOfReachOfTheToken() throws Exception {
        stubGitHubClient.repositoryMissing = true;

        mockMvc.perform(post("/api/workspaces/git").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://github.com/octocat/secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_git_repository")));

        assertThat(workspaceRepository.count()).isZero();
    }

    @Test
    void reportsGitHubOutageAsTemporaryAndCreatesNothing() throws Exception {
        stubGitHubClient.unavailable = true;

        mockMvc.perform(post("/api/workspaces/git").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://github.com/octocat/hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is("github_unavailable")));

        assertThat(workspaceRepository.count()).isZero();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/workspaces/git").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://github.com/octocat/hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requiresGoldAccess() throws Exception {
        mockMvc.perform(post("/api/workspaces/git").contextPath("/api")
                        .header("Authorization", bearer(plainToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://github.com/octocat/hello\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("atelier_forbidden")));
    }

    // ------------------------------- F-31 / SF-31-03 : explorateur d'un projet Git

    @Test
    void showsTheBranchFilesInTheExplorer() throws Exception {
        stubGitHubClient.treePaths = java.util.List.of("README.md", "src/App.java");

        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(get("/api/workspaces/" + id).contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()", is(2)))
                .andExpect(jsonPath("$.files[0]", is("README.md")))
                .andExpect(jsonPath("$.files[1]", is("src/App.java")))
                .andExpect(jsonPath("$.truncated", is(false)));

        // C'est bien la branche montée qui est listée, avec le jeton du propriétaire.
        assertThat(stubGitHubClient.lastRef).isEqualTo("main");
        assertThat(stubGitHubClient.lastToken).isEqualTo("github_pat_alice");
    }

    @Test
    void announcesATruncatedTreeRatherThanHidingIt() throws Exception {
        stubGitHubClient.treeTruncated = true;

        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(get("/api/workspaces/" + id).contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated", is(true)));
    }

    @Test
    void readsAFileFromTheBranch() throws Exception {
        stubGitHubClient.fileContent = "# Hello";

        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(get("/api/workspaces/" + id + "/file").contextPath("/api")
                        .param("path", "README.md")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", is("# Hello")));

        assertThat(stubGitHubClient.lastPath).isEqualTo("README.md");
    }

    @Test
    void reportsAFileMissingFromTheBranchAsNotFound() throws Exception {
        stubGitHubClient.fileContent = null;

        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(get("/api/workspaces/" + id + "/file").contextPath("/api")
                        .param("path", "absent.md")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_git_repository")));
    }

    @Test
    void refusesToWriteIntoAGitWorkspace() throws Exception {
        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/workspaces/" + id + "/file").contextPath("/api")
                        .param("path", "README.md")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"modifié\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("git_workspace_read_only")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/workspaces/" + id + "/file").contextPath("/api")
                        .param("path", "README.md")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("git_workspace_read_only")));

        mockMvc.perform(post("/api/workspaces/" + id + "/file/rename").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"README.md\",\"to\":\"LISEZMOI.md\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("git_workspace_read_only")));

        mockMvc.perform(get("/api/workspaces/" + id + "/export").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("git_workspace_read_only")));
    }

    @Test
    void refusesTheAssistantModeOnAGitWorkspace() throws Exception {
        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(post("/api/workspaces/" + id + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"bonjour\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("git_workspace_terminal_only")));
    }

    @Test
    void refusesToReadAGitWorkspaceOfAnotherUser() throws Exception {
        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(get("/api/workspaces/" + id + "/file").contextPath("/api")
                        .param("path", "README.md")
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------- F-31 / SF-31-04 : publication sur une branche

    @Test
    void refusesToPublishWithoutAnActiveSession() throws Exception {
        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(post("/api/workspaces/" + id + "/git/push").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"feat/atelier\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("no_active_session")));
    }

    @Test
    void refusesToPublishOnTheBaseBranch() throws Exception {
        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(post("/api/workspaces/" + id + "/git/push").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"main\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_git_branch")));
    }

    @Test
    void refusesToPublishAnArchiveProject() throws Exception {
        // Un projet d'archive n'a aucun dépôt où publier.
        Workspace archive = workspaceRepository.save(Workspace.builder()
                .userId(alice.getId())
                .name("archive")
                .build());

        mockMvc.perform(post("/api/workspaces/" + archive.getId() + "/git/push").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("git_workspace_required")));
    }

    @Test
    void refusesToPublishAProjectOfAnotherUser() throws Exception {
        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(post("/api/workspaces/" + id + "/git/push").contextPath("/api")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishingRequiresAuthenticationAndGoldAccess() throws Exception {
        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(post("/api/workspaces/" + id + "/git/push").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/workspaces/" + id + "/git/push").contextPath("/api")
                        .header("Authorization", bearer(plainToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("atelier_forbidden")));
    }

    // ------------------------------- F-31 / SF-31-05 : ouverture de la pull request

    @Test
    void refusesToOpenAPullRequestWithoutAnActiveSession() throws Exception {
        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(post("/api/workspaces/" + id + "/git/pull-request").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"feat/atelier\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("no_active_session")));
    }

    @Test
    void refusesAPullRequestFromTheBaseBranchOntoItself() throws Exception {
        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(post("/api/workspaces/" + id + "/git/pull-request").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"main\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_git_branch")));
    }

    @Test
    void refusesAPullRequestOnAnArchiveProject() throws Exception {
        Workspace archive = workspaceRepository.save(Workspace.builder()
                .userId(alice.getId())
                .name("archive")
                .build());

        mockMvc.perform(post("/api/workspaces/" + archive.getId() + "/git/pull-request").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"feat/atelier\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("git_workspace_required")));
    }

    @Test
    void refusesAPullRequestOnAProjectOfAnotherUser() throws Exception {
        // Isolation : le projet d'Alice est introuvable pour Bob, aucun tour n'est joué.
        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(post("/api/workspaces/" + id + "/git/pull-request").contextPath("/api")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"feat/atelier\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void openingAPullRequestRequiresAuthenticationAndGoldAccess() throws Exception {
        String id = openRepository(aliceToken, "{\"repoUrl\":\"https://github.com/octocat/hello\"}");

        mockMvc.perform(post("/api/workspaces/" + id + "/git/pull-request").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"feat/atelier\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/workspaces/" + id + "/git/pull-request").contextPath("/api")
                        .header("Authorization", bearer(plainToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"feat/atelier\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("atelier_forbidden")));
    }

    @Test
    void rejectsAMissingRepositoryUrl() throws Exception {
        mockMvc.perform(post("/api/workspaces/git").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", notNullValue()));
    }
}
