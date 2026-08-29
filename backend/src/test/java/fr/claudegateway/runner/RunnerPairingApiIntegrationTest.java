package fr.claudegateway.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de l'appairage et des jetons runner (F-38 / SF-38-01) : génération de code,
 * échange contre jeton, usage unique, listing/révocation, isolation {@code user_id}, accès Atelier,
 * et non-régression de la chaîne de sécurité principale après l'ajout de la chaîne dédiée
 * {@code /runner/**}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerPairingApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private RunnerTokenRepository runnerTokenRepository;
    @Autowired
    private RunnerPairingCodeRepository pairingCodeRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private TokenHasher tokenHasher;
    @Autowired
    private ObjectMapper objectMapper;

    private User admin;          // accès Atelier via bypass ADMIN
    private String adminToken;
    private Workspace adminWorkspace;
    private User other;          // autre utilisateur (ADMIN aussi, pour tester l'isolation par workspace)
    private String otherToken;
    private User plainUser;      // USER sans accès Atelier
    private String plainToken;
    private Workspace plainWorkspace;

    @BeforeEach
    void setUp() {
        runnerTokenRepository.deleteAll();
        pairingCodeRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

        admin = seedUser("admin@example.com", UserRole.ADMIN);
        adminToken = jwtService.generateToken(admin);
        adminWorkspace = seedWorkspace(admin.getId());

        other = seedUser("other@example.com", UserRole.ADMIN);
        otherToken = jwtService.generateToken(other);

        plainUser = seedUser("plain@example.com", UserRole.USER);
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

    private String pairingCodeUrl(UUID workspaceId) {
        return "/api/workspaces/" + workspaceId + "/runner/pairing-code";
    }

    private String generatePairingCode() throws Exception {
        MvcResult result = mockMvc.perform(post(pairingCodeUrl(adminWorkspace.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").exists())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("code").asText();
    }

    private static String pairBody(String code) {
        return "{\"code\":\"" + code + "\",\"label\":\"poste-client\"}";
    }

    // ---------- Génération de code ----------

    @Test
    void ownerGeneratesPairingCode() throws Exception {
        String code = generatePairingCode();
        assertThat(code).hasSize(8).matches("[A-Z2-9]{8}");
        assertThat(pairingCodeRepository.findAll()).hasSize(1);
        // Le clair n'est pas stocké : la table ne contient que l'empreinte.
        assertThat(pairingCodeRepository.findAll().get(0).getCodeHash()).isNotEqualTo(code);
    }

    @Test
    void generatingCodeForAnotherUsersWorkspaceIsNotFound() throws Exception {
        mockMvc.perform(post(pairingCodeUrl(adminWorkspace.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void generatingCodeWithoutAtelierAccessIsForbidden() throws Exception {
        mockMvc.perform(post(pairingCodeUrl(plainWorkspace.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("atelier_forbidden"));
    }

    // ---------- Échange (/runner/pair, sans JWT) ----------

    @Test
    void runnerExchangesCodeForToken() throws Exception {
        String code = generatePairingCode();
        MvcResult result = mockMvc.perform(post("/api/runner/pair").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(pairBody(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.workspaceId").value(adminWorkspace.getId().toString()))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String clearToken = json.get("token").asText();
        // Le jeton est stocké haché : l'empreinte du clair est présente, jamais le clair.
        assertThat(runnerTokenRepository.findByTokenHash(tokenHasher.sha256Hex(clearToken))).isPresent();
        assertThat(runnerTokenRepository.findAll().get(0).getTokenHash()).isNotEqualTo(clearToken);
        // Le code est consommé.
        assertThat(pairingCodeRepository.findAll().get(0).getConsumedAt()).isNotNull();
    }

    @Test
    void codeCannotBeRedeemedTwice() throws Exception {
        String code = generatePairingCode();
        mockMvc.perform(post("/api/runner/pair").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(pairBody(code)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/runner/pair").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(pairBody(code)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("pairing_invalid"));
    }

    @Test
    void unknownCodeIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/runner/pair").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(pairBody("ZZZZ2345")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("pairing_invalid"));
    }

    @Test
    void expiredCodeIsUnauthorized() throws Exception {
        // Code inséré directement, déjà expiré : l'empreinte correspond au clair "EXPIRED2".
        pairingCodeRepository.save(RunnerPairingCode.builder()
                .userId(admin.getId()).workspaceId(adminWorkspace.getId())
                .codeHash(tokenHasher.sha256Hex("EXPIRED2"))
                .expiresAt(OffsetDateTime.now().minusMinutes(1))
                .build());
        mockMvc.perform(post("/api/runner/pair").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(pairBody("EXPIRED2")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("pairing_invalid"));
    }

    @Test
    void pairWithoutCodeIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/runner/pair").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- Listing / révocation + isolation ----------

    @Test
    void listAndRevokeTokensIsolatedByUser() throws Exception {
        String code = generatePairingCode();
        MvcResult paired = mockMvc.perform(post("/api/runner/pair").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(pairBody(code)))
                .andExpect(status().isOk()).andReturn();
        UUID tokenId = runnerTokenRepository.findAll().get(0).getId();

        // Le propriétaire voit son jeton.
        mockMvc.perform(get("/api/workspaces/" + adminWorkspace.getId() + "/runner/tokens").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].revoked").value(false));

        // Un autre utilisateur ne voit pas ce workspace (404).
        mockMvc.perform(get("/api/workspaces/" + adminWorkspace.getId() + "/runner/tokens").contextPath("/api")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        // Un autre utilisateur ne peut pas révoquer ce jeton (404).
        mockMvc.perform(delete("/api/workspaces/" + adminWorkspace.getId() + "/runner/tokens/" + tokenId)
                        .contextPath("/api").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        assertThat(runnerTokenRepository.findById(tokenId).orElseThrow().getRevokedAt()).isNull();

        // Le propriétaire révoque (204), puis re-révoque (idempotent, 204).
        mockMvc.perform(delete("/api/workspaces/" + adminWorkspace.getId() + "/runner/tokens/" + tokenId)
                        .contextPath("/api").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/workspaces/" + adminWorkspace.getId() + "/runner/tokens/" + tokenId)
                        .contextPath("/api").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        assertThat(runnerTokenRepository.findById(tokenId).orElseThrow().getRevokedAt()).isNotNull();
    }

    // ---------- Non-régression de la chaîne principale ----------

    @Test
    void mainSecurityChainStillRequiresJwt() throws Exception {
        // /me reste protégé (401 sans JWT) malgré l'ajout de la chaîne dédiée /runner/**.
        mockMvc.perform(get("/api/me").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        // /me répond bien avec un JWT valide.
        mockMvc.perform(get("/api/me").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void pairingCodeEndpointRequiresJwt() throws Exception {
        // L'endpoint de génération (chaîne principale) reste protégé : 401 sans JWT.
        mockMvc.perform(post(pairingCodeUrl(adminWorkspace.getId())).contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
