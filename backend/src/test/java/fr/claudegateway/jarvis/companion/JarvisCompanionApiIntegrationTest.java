package fr.claudegateway.jarvis.companion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.veille.JarvisWatch;
import fr.claudegateway.atelier.veille.JarvisWatchAlert;
import fr.claudegateway.atelier.veille.JarvisWatchAlertRepository;
import fr.claudegateway.atelier.veille.JarvisWatchRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-232 / SF-232-03 — <b>les annonces téléphone verrouillé</b> : appairage par code, jeton dédié qui n'ouvre QUE la
 * relève des annonces, isolation, révocation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCompanionApiIntegrationTest {

    private static final String HEADER = JarvisCompanionDeviceController.TOKEN_HEADER;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private AtelierMessageRepository messageRepository;
    @Autowired private JarvisWatchRepository watchRepository;
    @Autowired private JarvisWatchAlertRepository alertRepository;
    @Autowired private JwtService jwtService;

    private String aliceJwt;
    private String bobJwt;
    private UUID aliceId;
    private UUID bobId;
    private Workspace aliceSubject;
    private Workspace bobSubject;

    @BeforeEach
    void setUp() {
        User alice = seedUser("companion-alice-" + UUID.randomUUID() + "@ex.com");
        User bob = seedUser("companion-bob-" + UUID.randomUUID() + "@ex.com");
        aliceId = alice.getId();
        bobId = bob.getId();
        aliceJwt = jwtService.generateToken(alice);
        bobJwt = jwtService.generateToken(bob);
        aliceSubject = workspaceRepository.save(Workspace.builder().userId(aliceId).name("Connecteur AGENOR")
                .projectPath("agenor").executionTarget(WorkspaceExecutionTarget.RUNNER).build());
        bobSubject = workspaceRepository.save(Workspace.builder().userId(bobId).name("Chez Bob")
                .projectPath("bob").executionTarget(WorkspaceExecutionTarget.RUNNER).build());
    }

    @Test
    @DisplayName("appairage : le code s'échange une fois contre un jeton ; une deuxième fois, il est refusé")
    void pairingIsSingleUse() throws Exception {
        String code = pairingCode(aliceJwt);
        String token = pair(code, "Pixel de Franck");
        assertThat(token).hasSizeGreaterThan(30);

        mockMvc.perform(post("/api/runner/jarvis/pair").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("jarvis_companion_pairing_invalid"));

        asUser(aliceJwt, get("/api/jarvis/companions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("Pixel de Franck"))
                .andExpect(jsonPath("$[0].tokenHash").doesNotExist());
    }

    @Test
    @DisplayName("annonces : rien au premier appel ; puis la fin d'un tour Jarvis et une alerte, pas un tour tapé, rien de Bob")
    void announcementsOfTheOwnerOnly() throws Exception {
        String token = pair(pairingCode(aliceJwt), null);

        JsonNode first = feed(token, null);
        assertThat(first.get("items")).isEmpty();
        String cursor = first.get("cursor").asText();

        message(aliceSubject, aliceId, "ASSISTANT", "Il compile.", null);
        message(aliceSubject, aliceId, "ASSISTANT", "🎙 Le message pour Zahi est prêt.\n\nDétails…",
                AtelierMessage.VIA_JARVIS);
        message(bobSubject, bobId, "ASSISTANT", "🎙 Secret de Bob.", AtelierMessage.VIA_JARVIS);
        alert(aliceSubject, aliceId, "Agenor : AG-2 est passé en bloqué.");

        JsonNode next = feed(token, cursor);
        assertThat(next.get("items")).hasSize(2);
        assertThat(next.get("items").get(0).get("kind").asText()).isEqualTo("TURN");
        assertThat(next.get("items").get(0).get("text").asText())
                .isEqualTo("Connecteur AGENOR : Le message pour Zahi est prêt.");
        assertThat(next.get("items").get(1).get("kind").asText()).isEqualTo("ALERT");
        assertThat(next.get("items").get(1).get("text").asText())
                .isEqualTo("Veille : Agenor : AG-2 est passé en bloqué.");
        assertThat(next.toString()).doesNotContain("Bob");
    }

    @Test
    @DisplayName("le jeton du compagnon n'ouvre rien d'autre ; un jeton runner n'ouvre pas les annonces")
    void tokenIsScoped() throws Exception {
        String token = pair(pairingCode(aliceJwt), null);

        // Chaîne principale : ni en Bearer, ni par l'en-tête du compagnon.
        mockMvc.perform(get("/api/jarvis/companions").contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/jarvis/companions").contextPath("/api").header(HEADER, token))
                .andExpect(status().isUnauthorized());
        // Une route runner à jeton : refusé en X-Runner-Token.
        mockMvc.perform(post("/api/runner/vigie/readiness").contextPath("/api").header("X-Runner-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        // Les annonces : sans jeton, ou un jeton inconnu (comme le serait un jeton runner).
        mockMvc.perform(get("/api/runner/jarvis/announcements").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/runner/jarvis/announcements").contextPath("/api").header(HEADER, "jeton-runner-quelconque"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/runner/jarvis/announcements").contextPath("/api").header("X-Runner-Token", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("révocation : refusé dès la requête suivante ; Bob ne peut pas révoquer le téléphone d'Alice")
    void revocation() throws Exception {
        String token = pair(pairingCode(aliceJwt), null);
        String id = json.readTree(asUser(aliceJwt, get("/api/jarvis/companions")).andReturn().getResponse()
                .getContentAsString()).get(0).get("id").asText();

        asUser(bobJwt, delete("/api/jarvis/companions/" + id)).andExpect(status().isNotFound());
        feed(token, null);

        asUser(aliceJwt, delete("/api/jarvis/companions/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/runner/jarvis/announcements").contextPath("/api").header(HEADER, token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("jarvis_companion_unauthorized"));
        asUser(aliceJwt, get("/api/jarvis/companions")).andExpect(jsonPath("$.length()").value(0));
    }

    // ---- outillage ----

    private String pairingCode(String jwt) throws Exception {
        String body = asUser(jwt, post("/api/jarvis/companions/pairing-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asText();
    }

    private String pair(String code, String label) throws Exception {
        String payload = label == null ? "{\"code\":\"" + code.toLowerCase() + "\"}"
                : "{\"code\":\"" + code + "\",\"label\":\"" + label + "\"}";
        String body = mockMvc.perform(post("/api/runner/jarvis/pair").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private JsonNode feed(String token, String cursor) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/runner/jarvis/announcements").contextPath("/api").header(HEADER, token);
        if (cursor != null) {
            request = request.param("cursor", cursor);
        }
        return json.readTree(mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private void message(Workspace w, UUID userId, String role, String content, String via) {
        try {
            Thread.sleep(5); // des instants distincts : l'ordre se lit sur created_at
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        messageRepository.save(AtelierMessage.builder().workspaceId(w.getId()).userId(userId).role(role)
                .content(content).via(via).build());
    }

    private void alert(Workspace w, UUID userId, String sentence) {
        OffsetDateTime now = OffsetDateTime.now();
        JarvisWatch watch = watchRepository.save(JarvisWatch.builder().userId(userId).workspaceId(w.getId())
                .name("Agenor").platform("Jira").scope("p").probeCommand("curl -s x").runTimes("09:00")
                .timeZone("Europe/Paris").origin(JarvisWatch.ORIGIN_AGENT).active(true).proposed(false)
                .createdAt(now).updatedAt(now).build());
        alertRepository.save(JarvisWatchAlert.builder().userId(userId).workspaceId(w.getId()).watchId(watch.getId())
                .sentence(sentence).changesJson("[]").changeCount(1).costUsd(BigDecimal.ZERO)
                .createdAt(now.plusNanos(5_000_000)).build());
    }

    private ResultActions asUser(String jwt, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.contextPath("/api").header("Authorization", "Bearer " + jwt));
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }
}
