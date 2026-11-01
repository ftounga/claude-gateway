package fr.claudegateway.jarvis.companion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Le compagnon Android (F-232 / SF-232-03) : appairage par code, jeton dédié limité aux annonces, révocation,
 * attente longue, isolation entre comptes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCompanionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisCompanionAnnouncements announcements;
    @Autowired private ObjectMapper json;

    private User alice;
    private User bob;
    private String aliceJwt;
    private String bobJwt;

    @BeforeEach
    void setUp() {
        alice = user("companion-a-" + UUID.randomUUID() + "@ex.com");
        bob = user("companion-b-" + UUID.randomUUID() + "@ex.com");
        aliceJwt = jwtService.generateToken(alice);
        bobJwt = jwtService.generateToken(bob);
    }

    private User user(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build());
    }

    private ResultActions asUser(String jwt, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r)
            throws Exception {
        return mockMvc.perform(r.contextPath("/api").header("Authorization", "Bearer " + jwt));
    }

    private String code(String jwt) throws Exception {
        String body = asUser(jwt, post("/api/jarvis/companion/pairing-code")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isString()).andExpect(jsonPath("$.expiresAt").isString())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asText();
    }

    private ResultActions pair(String code, String name) throws Exception {
        return mockMvc.perform(post("/api/companion/pair").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("code", code, "name", name))));
    }

    private JsonNode paired(String jwt) throws Exception {
        String body = pair(code(jwt).toLowerCase(), "Pixel de test").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private ResultActions feed(String token, String after, int wait) throws Exception {
        var request = get("/api/companion/announcements").contextPath("/api").param("wait", String.valueOf(wait));
        if (after != null) {
            request.param("after", after);
        }
        if (token != null) {
            request.header(CompanionFeedController.TOKEN_HEADER, token);
        }
        return mockMvc.perform(request);
    }

    @Test
    @DisplayName("code → jeton dédié ; le jeton lit les annonces ; aucun rejeu au premier appel")
    void pairingThenFeed() throws Exception {
        JsonNode device = paired(aliceJwt);
        String token = device.get("token").asText();
        assertThat(token).startsWith("jcp_");

        String first = feed(token, null, 0).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        String cursor = json.readTree(first).get("cursor").asText();

        announcements.turnDone(alice.getId(), UUID.randomUUID(), "🎙 Étape trois validée.");

        feed(token, cursor, 5).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("TURN"))
                .andExpect(jsonPath("$.items[0].text").value("C'est fait : Étape trois validée."))
                .andExpect(jsonPath("$.items[0].link").value(org.hamcrest.Matchers.startsWith("/jarvis?sujet=")));

        asUser(aliceJwt, get("/api/jarvis/companion/devices")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Pixel de test"))
                .andExpect(jsonPath("$[0].lastSeenAt").isString());
    }

    @Test
    @DisplayName("un code ne sert qu'une fois ; un code inconnu est refusé")
    void codeIsSingleUse() throws Exception {
        String code = code(aliceJwt);
        pair(code, "un").andExpect(status().isOk());
        pair(code, "deux").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("companion_pairing_invalid"));
        pair("ZZZZZZZZ", "trois").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("révoquer rend le jeton sans valeur aussitôt ; sans jeton : 401")
    void revocation() throws Exception {
        JsonNode device = paired(aliceJwt);
        String token = device.get("token").asText();
        feed(token, null, 0).andExpect(status().isOk());

        asUser(bobJwt, delete("/api/jarvis/companion/devices/" + device.get("deviceId").asText()))
                .andExpect(status().isNotFound());
        asUser(aliceJwt, delete("/api/jarvis/companion/devices/" + device.get("deviceId").asText()))
                .andExpect(status().isNoContent());

        feed(token, null, 0).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("companion_token_invalid"));
        feed(null, null, 0).andExpect(status().isUnauthorized());
        asUser(aliceJwt, get("/api/jarvis/companion/devices")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("le jeton n'ouvre aucune route utilisateur ; un JWT n'ouvre pas les annonces ; le reste de /companion est fermé")
    void tokenIsScoped() throws Exception {
        String token = paired(aliceJwt).get("token").asText();

        mockMvc.perform(get("/api/jarvis/companion/devices").contextPath("/api")
                .header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/jarvis/companion/devices").contextPath("/api")
                .header(CompanionFeedController.TOKEN_HEADER, token)).andExpect(status().isUnauthorized());
        asUser(aliceJwt, get("/api/companion/announcements")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/companion/autre").contextPath("/api")
                .header(CompanionFeedController.TOKEN_HEADER, token)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("isolation : les annonces et les téléphones de A ne se voient pas chez B ; sans téléphone, rien n'est rangé")
    void isolation() throws Exception {
        String bobToken = paired(bobJwt).get("token").asText();
        String cursor = json.readTree(feed(bobToken, null, 0).andReturn().getResponse().getContentAsString())
                .get("cursor").asText();

        // Alice n'a aucun téléphone : son annonce n'est même pas rangée ; et elle n'irait jamais chez Bob.
        announcements.watchAlert(alice.getId(), "Agenor : AG-2 est bloqué.", "/jarvis?alerte=1");
        feed(bobToken, cursor, 0).andExpect(jsonPath("$.items.length()").value(0));
        asUser(aliceJwt, get("/api/jarvis/companion/devices")).andExpect(jsonPath("$.length()").value(0));

        announcements.watchAlert(bob.getId(), "Agenor : AG-3 est livré.", "https://ailleurs.example");
        feed(bobToken, cursor, 0).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("ALERT"))
                .andExpect(jsonPath("$.items[0].link").value("/jarvis"));
    }

    @Test
    @DisplayName("au-delà de cinq téléphones, plus de code")
    void limit() throws Exception {
        for (int i = 0; i < JarvisCompanionPairingService.MAX_DEVICES; i++) {
            paired(aliceJwt);
        }
        asUser(aliceJwt, post("/api/jarvis/companion/pairing-code")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("companion_limit"));
    }

    @Test
    @DisplayName("sans JWT, les routes utilisateur du compagnon sont fermées")
    void userRoutesNeedJwt() throws Exception {
        mockMvc.perform(post("/api/jarvis/companion/pairing-code").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
