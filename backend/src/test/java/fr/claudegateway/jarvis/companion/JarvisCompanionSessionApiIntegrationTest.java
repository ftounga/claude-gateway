package fr.claudegateway.jarvis.companion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-232 / SF-232-05 — <b>la session Jarvis du compagnon</b> : le jeton dédié s'échange contre un JWT court à portée
 * {@code jarvis-compagnon}, qui n'ouvre que la liste fermée de l'écran Jarvis, meurt avec la révocation de l'appareil
 * ou un « déconnecter toutes les sessions », et dont chaque émission est tracée pour la mesure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCompanionSessionApiIntegrationTest {

    private static final String HEADER = JarvisCompanionDeviceController.TOKEN_HEADER;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisCompanionOpeningRepository openings;

    private User alice;
    private String aliceJwt;
    private String bobJwt;

    @BeforeEach
    void setUp() {
        alice = seedUser("session-alice-" + UUID.randomUUID() + "@ex.com");
        User bob = seedUser("session-bob-" + UUID.randomUUID() + "@ex.com");
        aliceJwt = jwtService.generateToken(alice);
        bobJwt = jwtService.generateToken(bob);
    }

    @Test
    @DisplayName("session : le jeton du compagnon rend un JWT court à portée jarvis-compagnon, verrouillé par défaut")
    void sessionIsScopedAndLockedByDefault() throws Exception {
        String companionToken = pairedCompanion(aliceJwt);

        JsonNode session = session(companionToken, null);

        assertThat(session.get("scope").asText()).isEqualTo("jarvis-compagnon");
        assertThat(session.get("locked").asBoolean()).isTrue();
        assertThat(session.get("expiresAt").asText()).isNotBlank();
        JsonNode claims = claims(session.get("token").asText());
        assertThat(claims.get("sub").asText()).isEqualTo(alice.getId().toString());
        assertThat(claims.get("scope").asText()).isEqualTo("jarvis-compagnon");
        assertThat(claims.get("lk").asBoolean()).isTrue();
        assertThat(claims.get("cid").asText()).isNotBlank();
        // Au plus 30 minutes.
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isLessThanOrEqualTo(30 * 60);
    }

    @Test
    @DisplayName("session : un jeton absent, inconnu ou un JWT ordinaire sont refusés (401 générique)")
    void sessionRefusesOtherCredentials() throws Exception {
        mockMvc.perform(post("/api/runner/jarvis/session").contextPath("/api"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("jarvis_companion_unauthorized"));
        mockMvc.perform(post("/api/runner/jarvis/session").contextPath("/api").header(HEADER, "inconnu"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/runner/jarvis/session").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceJwt))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("portée : les routes de l'écran Jarvis s'ouvrent, le reste répond 403 jarvis_companion_scope")
    void scopeIsClosed() throws Exception {
        String jwt = session(pairedCompanion(aliceJwt), true).get("token").asText();

        asUser(jwt, get("/api/jarvis/preferences")).andExpect(status().isOk());
        asUser(jwt, get("/api/workspaces")).andExpect(status().isOk());
        asUser(jwt, get("/api/jarvis/companions")).andExpect(status().isOk());

        for (MockHttpServletRequestBuilder outside : List.of(
                get("/api/me"),
                post("/api/me/logout-all"),
                get("/api/admin/jarvis/captures"),
                post("/api/jarvis/companions/pairing-code"),
                delete("/api/jarvis/companions/" + UUID.randomUUID()),
                delete("/api/jarvis/portrait"),
                put("/api/jarvis/budget"),
                post("/api/jarvis/mail/accounts"),
                delete("/api/jarvis/calendar/grants/" + UUID.randomUUID()),
                get("/api/jarvis/calendar/oauth/start"),
                post("/api/workspaces"),
                get("/api/notifications"))) {
            asUser(jwt, outside)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("jarvis_companion_scope"));
        }
    }

    @Test
    @DisplayName("révocation : révoquer le téléphone coupe sa session à la requête suivante")
    void revocationKillsTheSession() throws Exception {
        String jwt = session(pairedCompanion(aliceJwt), false).get("token").asText();
        asUser(jwt, get("/api/jarvis/preferences")).andExpect(status().isOk());

        String id = json.readTree(asUser(aliceJwt, get("/api/jarvis/companions")).andReturn().getResponse()
                .getContentAsString()).get(0).get("id").asText();
        // Bob ne révoque pas le téléphone d'Alice.
        asUser(bobJwt, delete("/api/jarvis/companions/" + id)).andExpect(status().isNotFound());
        asUser(jwt, get("/api/jarvis/preferences")).andExpect(status().isOk());

        asUser(aliceJwt, delete("/api/jarvis/companions/" + id)).andExpect(status().isNoContent());
        asUser(jwt, get("/api/jarvis/preferences")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("déconnecter toutes les sessions coupe aussi celle du compagnon")
    void logoutAllKillsTheSession() throws Exception {
        String jwt = session(pairedCompanion(aliceJwt), true).get("token").asText();
        asUser(aliceJwt, post("/api/me/logout-all")).andExpect(status().isOk());
        asUser(jwt, get("/api/jarvis/preferences")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("isolation : la session d'Alice ne lit que les données d'Alice")
    void isolation() throws Exception {
        String jwt = session(pairedCompanion(aliceJwt), true).get("token").asText();
        pairedCompanion(bobJwt);
        pairedCompanion(bobJwt);
        JsonNode seen = json.readTree(asUser(jwt, get("/api/jarvis/companions")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(seen).hasSize(1);
    }

    @Test
    @DisplayName("mesure : chaque session est tracée (motif, verrouillé), sans jeton")
    void openingsAreRecorded() throws Exception {
        String companionToken = pairedCompanion(aliceJwt);
        session(companionToken, true);
        mockMvc.perform(post("/api/runner/jarvis/session").contextPath("/api").header(HEADER, companionToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":false,\"purpose\":\"unlock\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));
        mockMvc.perform(post("/api/runner/jarvis/session").contextPath("/api").header(HEADER, companionToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"n'importe\"}"))
                .andExpect(status().isOk());

        List<JarvisCompanionOpening> rows = openings.findByUserIdOrderByCreatedAtAsc(alice.getId());
        assertThat(rows).extracting(JarvisCompanionOpening::getPurpose).containsExactly(
                JarvisCompanionOpening.Purpose.OPEN, JarvisCompanionOpening.Purpose.UNLOCK,
                JarvisCompanionOpening.Purpose.OPEN);
        assertThat(rows).extracting(JarvisCompanionOpening::isLocked).containsExactly(true, false, true);
    }

    @Test
    @DisplayName("non-régression : un JWT ordinaire, sans portée, reste ouvert partout")
    void ordinarySessionUnchanged() throws Exception {
        // Un JWT ordinaire sans portée reste ouvert partout (non-régression).
        asUser(aliceJwt, get("/api/me")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ outils

    private String pairedCompanion(String jwt) throws Exception {
        String code = json.readTree(asUser(jwt, post("/api/jarvis/companions/pairing-code"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("code").asText();
        String body = mockMvc.perform(post("/api/runner/jarvis/pair").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private JsonNode session(String companionToken, Boolean locked) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/runner/jarvis/session").contextPath("/api")
                .header(HEADER, companionToken);
        if (locked != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content("{\"locked\":" + locked + "}");
        }
        return json.readTree(mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private JsonNode claims(String jwt) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private ResultActions asUser(String jwt, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.contextPath("/api").header("Authorization", "Bearer " + jwt));
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }
}
