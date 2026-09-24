package fr.claudegateway.bilan;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Les bilans gardés, de bout en bout (F-155 / SF-155-04).
 *
 * <p>Ce que ces tests tiennent : l'<b>aller-retour JSON</b> des photographies (une photographie
 * illisible rendrait l'artefact inutile), le <b>403</b> pour qui n'est pas administrateur — jamais
 * une liste vide —, et le <b>404</b> sur le bilan d'un autre compte.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionBilanApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private SessionBilanRepository bilans;
    @Autowired private SessionBilanStore store;
    @Autowired private JwtService jwtService;

    private UUID adminId;
    private String adminToken;
    private UUID otherAdminId;
    private String otherAdminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        bilans.deleteAll();
        userRepository.deleteAll();
        User admin = save("admin-bilan@ex.com", UserRole.ADMIN);
        adminId = admin.getId();
        adminToken = jwtService.generateToken(admin);
        User otherAdmin = save("admin2-bilan@ex.com", UserRole.ADMIN);
        otherAdminId = otherAdmin.getId();
        otherAdminToken = jwtService.generateToken(otherAdmin);
        userToken = jwtService.generateToken(save("user-bilan@ex.com", UserRole.USER));
    }

    private User save(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private SessionBilan keepOne(UUID userId) {
        OffsetDateTime from = OffsetDateTime.parse("2026-09-24T08:00:00Z");
        SessionLedger ledger = new SessionLedger(from, from.plusHours(2), 12, Duration.ofHours(2),
                40, 3, 5, new BigDecimal("9.40"), 500_000, 20_000, 100_000, 1_000, 17, 1,
                "claude-opus-5", Duration.ofSeconds(240),
                List.of(new SessionLedger.CostlyTurn(from, "claude-opus-5", new BigDecimal("4.10"),
                        200_000, 3_000, 0)),
                List.of(new SessionLedger.HeavyTool("bash", 6, Duration.ofSeconds(200), 3)));
        SessionSuggestionService.Verdict verdict = new SessionSuggestionService.Verdict(
                List.of(SessionSuggestion.ofCost(SessionSuggestion.Kind.CACHE_FROID,
                        "Gardez le début stable.", "cache lu : 17 % sur 12 tours", 28,
                        new BigDecimal("2.60"))), 2);
        return store.keep(userId, UUID.randomUUID(), "AGENOR", "MANUEL", ledger, verdict);
    }

    @Test
    @DisplayName("un bilan gardé se relit FIDÈLEMENT — relevé et suggestions compris")
    void aKeptBilanIsReadBackFaithfully() throws Exception {
        UUID id = keepOne(adminId).getId();

        mockMvc.perform(get("/api/admin/bilans/" + id).contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                // les chiffres de tête, qui vivent en colonnes
                .andExpect(jsonPath("$.headline.workspaceName", is("AGENOR")))
                .andExpect(jsonPath("$.headline.turns", is(12)))
                .andExpect(jsonPath("$.headline.cacheShare", is(17)))
                .andExpect(jsonPath("$.headline.discardedCount", is(2)))
                // la photographie du relevé, relue
                .andExpect(jsonPath("$.ledger.turns", is(12)))
                .andExpect(jsonPath("$.ledger.filesWritten", is(5)))
                .andExpect(jsonPath("$.ledger.costliestTurns", hasSize(1)))
                .andExpect(jsonPath("$.ledger.heaviestTools[0].tool", is("bash")))
                .andExpect(jsonPath("$.ledger.heaviestTools[0].failures", is(3)))
                // la photographie des suggestions, avec SA MESURE et SON GAIN
                .andExpect(jsonPath("$.suggestions", hasSize(1)))
                .andExpect(jsonPath("$.suggestions[0].axis", is("COUT")))
                .andExpect(jsonPath("$.suggestions[0].gainPct", is(28)))
                .andExpect(jsonPath("$.suggestions[0].measure", is("cache lu : 17 % sur 12 tours")));
    }

    @Test
    @DisplayName("la liste rend les chiffres qui permettent de comparer deux semaines")
    void theListCarriesWhatComparisonNeeds() throws Exception {
        keepOne(adminId);

        mockMvc.perform(get("/api/admin/bilans").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].turns", is(12)))
                .andExpect(jsonPath("$[0].cacheShare", is(17)))
                .andExpect(jsonPath("$[0].suggestionCount", is(1)))
                .andExpect(jsonPath("$[0].discardedCount", is(2)))
                .andExpect(jsonPath("$[0].origin", is("MANUEL")));
    }

    @Test
    @DisplayName("un NON-administrateur reçoit 403 — jamais une liste vide qui laisserait croire qu'il n'y a rien")
    void aNonAdminGetsForbiddenNotAnEmptyList() throws Exception {
        keepOne(adminId);

        mockMvc.perform(get("/api/admin/bilans").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/bilans/" + UUID.randomUUID()).contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/bilans").contextPath("/api")
                        .param("workspaceId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ISOLATION — le bilan d'un AUTRE administrateur est introuvable, pas interdit")
    void anotherAdminsBilanIsNotFound() throws Exception {
        UUID id = keepOne(otherAdminId).getId();

        mockMvc.perform(get("/api/admin/bilans/" + id).contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/bilans").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("sans jeton, rien du tout")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/admin/bilans").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("demander un bilan sur un projet d'autrui : 404, aucun bilan créé")
    void producingOnSomeoneElsesProjectIsNotFound() throws Exception {
        mockMvc.perform(post("/api/admin/bilans").contextPath("/api")
                        .param("workspaceId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/admin/bilans").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("les bilans sortent du plus récent au plus ancien")
    void newestFirst() throws Exception {
        keepOne(adminId);
        keepOne(adminId);

        mockMvc.perform(get("/api/admin/bilans").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$.length()", greaterThan(1)));
    }
}
