package fr.claudegateway.teams;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de {@code GET /api/workspaces/{id}/teams/link} (F-87 / SF-87-03).
 *
 * <p>Deux garanties : l'endpoint <b>dit un état</b> même quand rien n'est relié — une machine
 * éteinte n'est pas une panne d'application —, et un projet appartenant à quelqu'un d'autre est
 * <b>indistinguable</b> d'un projet inexistant.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamsLinkApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private JwtService jwtService;

    private Workspace workspace;
    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

        User owner = seedUser("teams-owner@example.com");
        ownerToken = jwtService.generateToken(owner);
        workspace = workspaceRepository.save(Workspace.builder()
                .userId(owner.getId()).name("Projet")
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build());

        otherToken = jwtService.generateToken(seedUser("teams-other@example.com"));
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder()
                .email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private String url(UUID workspaceId) {
        return "/api/workspaces/" + workspaceId + "/teams/link";
    }

    @Test
    @DisplayName("Sans machine rattachée : 200, un état, et ce qu'il faut faire")
    void without_a_host_it_still_says_where_we_stand() throws Exception {
        mockMvc.perform(get(url(workspace.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BROWSER_NOT_DETECTED"))
                .andExpect(jsonPath("$.label").value("Teams : navigateur non détecté"))
                .andExpect(jsonPath("$.sentence").value("Aucune machine n'est rattachée à ce projet."))
                .andExpect(jsonPath("$.remedy").value(
                        org.hamcrest.Matchers.containsString("lancez le runner")))
                .andExpect(jsonPath("$.conclusive").value(false));
    }

    @Test
    @DisplayName("Le projet d'un autre utilisateur est introuvable — isolation user_id")
    void another_users_project_is_not_found() throws Exception {
        mockMvc.perform(get(url(workspace.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Un projet inexistant est introuvable — indistinguable du précédent")
    void an_unknown_project_is_not_found() throws Exception {
        mockMvc.perform(get(url(UUID.randomUUID())).contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Sans jeton, rien")
    void without_a_token_nothing() throws Exception {
        mockMvc.perform(get(url(workspace.getId())).contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
