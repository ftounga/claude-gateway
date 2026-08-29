package fr.claudegateway.runner;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.channel.RunnerConnection;
import fr.claudegateway.runner.channel.RunnerRegistry;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration du canal et du statut runner (F-38 / SF-38-02) : couverture du handshake WS par
 * la chaîne dédiée {@code /runner/**} (401 sans jeton, handshake atteint avec jeton valide), statut
 * connecté/déconnecté, isolation {@code user_id}, gate Atelier, et non-régression de la chaîne
 * principale.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerStatusApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private RunnerTokenRepository runnerTokenRepository;
    @Autowired
    private RunnerTokenService tokenService;
    @Autowired
    private RunnerRegistry runnerRegistry;
    @Autowired
    private JwtService jwtService;

    private User admin;
    private String adminToken;
    private Workspace adminWorkspace;
    private User other;
    private String otherToken;
    private User plainUser;
    private String plainToken;
    private Workspace plainWorkspace;

    @BeforeEach
    void setUp() {
        runnerTokenRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

        admin = seedUser("admin-status@example.com", UserRole.ADMIN);
        adminToken = jwtService.generateToken(admin);
        adminWorkspace = seedWorkspace(admin.getId());

        other = seedUser("other-status@example.com", UserRole.ADMIN);
        otherToken = jwtService.generateToken(other);

        plainUser = seedUser("plain-status@example.com", UserRole.USER);
        plainToken = jwtService.generateToken(plainUser);
        plainWorkspace = seedWorkspace(plainUser.getId());
    }

    private User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder()
                .email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private Workspace seedWorkspace(UUID userId) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name("Projet").build());
    }

    private String statusUrl(UUID workspaceId) {
        return "/api/workspaces/" + workspaceId + "/runner/status";
    }

    // ---------- Statut ----------

    @Test
    void ownerSeesDisconnectedByDefault() throws Exception {
        mockMvc.perform(get(statusUrl(adminWorkspace.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.lastSeenAt").doesNotExist());
    }

    @Test
    void statusReflectsRegisteredConnection() throws Exception {
        UUID tokenId = UUID.randomUUID();
        runnerRegistry.register(new RunnerConnection(adminWorkspace.getId(), admin.getId(),
                tokenId, "node-test", OffsetDateTime.now()));
        try {
            mockMvc.perform(get(statusUrl(adminWorkspace.getId())).contextPath("/api")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.connected").value(true));
        } finally {
            // Nettoyage : le registre in-memory est un singleton partagé entre tests.
            runnerRegistry.unregister(adminWorkspace.getId(), tokenId);
        }
    }

    @Test
    void statusForAnotherUsersWorkspaceIsNotFound() throws Exception {
        mockMvc.perform(get(statusUrl(adminWorkspace.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void statusWithoutAtelierAccessIsForbidden() throws Exception {
        mockMvc.perform(get(statusUrl(plainWorkspace.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("atelier_forbidden"));
    }

    @Test
    void statusWithoutJwtIsUnauthorized() throws Exception {
        mockMvc.perform(get(statusUrl(adminWorkspace.getId())).contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- Handshake WS (chaîne dédiée /runner/**) ----------

    @Test
    void webSocketHandshakeWithoutTokenIsUnauthorized() throws Exception {
        // La chaîne dédiée laisse passer /runner/ws ; l'interceptor de handshake refuse sans jeton.
        mockMvc.perform(get("/api/runner/ws").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webSocketHandshakeWithValidTokenReachesHandshake() throws Exception {
        String clear = tokenService.issue(admin.getId(), adminWorkspace.getId(), "poste").clearToken();
        // Jeton valide : l'interceptor authentifie et laisse le handshake se poursuivre. Sans en-tête
        // Upgrade, le handshake échoue en 400 — ce qui prouve qu'on a passé sécurité ET interceptor.
        mockMvc.perform(get("/api/runner/ws").contextPath("/api").param("token", clear))
                .andExpect(status().isBadRequest());
    }

    // ---------- Non-régression chaîne principale ----------

    @Test
    void mainSecurityChainStillRequiresJwt() throws Exception {
        mockMvc.perform(get("/api/me").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
