package fr.claudegateway.atelier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de {@code GET /api/workspaces/{id}/engine} (F-39 / SF-39-07) : contrat rendu,
 * gate Atelier, et isolation {@code user_id} — un projet d'un autre utilisateur est indistinguable
 * d'un projet inexistant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtelierEngineApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private JwtService jwtService;

    private User owner;
    private String ownerToken;
    private Workspace sandboxWorkspace;
    private Workspace runnerWorkspace;
    private String otherToken;
    private String plainToken;

    @BeforeEach
    void setUp() {
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

        owner = seedUser("engine-owner@example.com", UserRole.ADMIN);
        ownerToken = jwtService.generateToken(owner);
        sandboxWorkspace = seedWorkspace(owner.getId(), WorkspaceExecutionTarget.SANDBOX);
        runnerWorkspace = seedWorkspace(owner.getId(), WorkspaceExecutionTarget.RUNNER);

        otherToken = jwtService.generateToken(seedUser("engine-other@example.com", UserRole.ADMIN));
        plainToken = jwtService.generateToken(seedUser("engine-plain@example.com", UserRole.USER));
    }

    private User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder()
                .email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private Workspace seedWorkspace(UUID userId, WorkspaceExecutionTarget target) {
        return workspaceRepository.save(Workspace.builder()
                .userId(userId).name("Projet").executionTarget(target).build());
    }

    private String engineUrl(UUID workspaceId) {
        return "/api/workspaces/" + workspaceId + "/engine";
    }

    @Test
    void sandboxProjectRunsOnTheHostedSandboxAndAsksForNothing() throws Exception {
        mockMvc.perform(get(engineUrl(sandboxWorkspace.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engine").value("HOSTED_SANDBOX"))
                .andExpect(jsonPath("$.runnerConnected").value(false))
                .andExpect(jsonPath("$.runnerLastSeenAt").doesNotExist())
                .andExpect(jsonPath("$.recommendRunner").value(false))
                .andExpect(jsonPath("$.recommendReason").doesNotExist());
    }

    /** D-L4-1 : la cible déclarée décide, même sans runner joignable. */
    @Test
    void runnerProjectStaysOnTheLocalMachineWhenTheRunnerIsOffline() throws Exception {
        mockMvc.perform(get(engineUrl(runnerWorkspace.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engine").value("LOCAL_MACHINE"))
                .andExpect(jsonPath("$.runnerConnected").value(false))
                .andExpect(jsonPath("$.recommendRunner").value(false));
    }

    @Test
    void unknownWorkspaceIsNotFound() throws Exception {
        mockMvc.perform(get(engineUrl(UUID.randomUUID())).contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherUsersWorkspaceIsNotFound() throws Exception {
        mockMvc.perform(get(engineUrl(sandboxWorkspace.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void withoutAtelierAccessItIsForbidden() throws Exception {
        mockMvc.perform(get(engineUrl(sandboxWorkspace.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("atelier_forbidden"));
    }

    @Test
    void withoutJwtItIsUnauthorized() throws Exception {
        mockMvc.perform(get(engineUrl(sandboxWorkspace.getId())).contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
