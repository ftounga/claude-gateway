package fr.claudegateway.jarvis.universe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.actions.Interlocuteur;
import fr.claudegateway.atelier.actions.InterlocuteurRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-234 / SF-234-01 — <b>l'univers de Jarvis</b> : clients → sujets → chantiers ouverts → étape, interlocuteurs et
 * destinataires « À envoyer », en un appel ; <b>rien de Bob</b> ; bornes ; <b>aucun contenu</b>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisUniverseApiIntegrationTest {

    private static final String REPLY = "🎙 Message pour Zahi prêt.\n\n"
            + "```mail\nÀ : Zahi\nObjet : Compte forge\n\nCORPS-SECRET-DU-MAIL\n```\n";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private AtelierMessageRepository messageRepository;
    @Autowired private InterlocuteurRepository interlocuteurRepository;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private UUID aliceId;
    private UUID bobId;
    private UUID hostId;
    private Workspace forge;

    @BeforeEach
    void setUp() {
        User alice = seedUser("jarvis-universe-alice-" + UUID.randomUUID() + "@ex.com");
        User bob = seedUser("jarvis-universe-bob-" + UUID.randomUUID() + "@ex.com");
        aliceId = alice.getId();
        bobId = bob.getId();
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        hostId = seedHost(aliceId, "CAGIP").getId();
        forge = seedWorkspace(aliceId, hostId, "Migration Forge", false, false);
        seedWorkspace(aliceId, hostId, "poste", true, false);
        seedWorkspace(aliceId, hostId, "teams", false, true);
        seedWorkspace(aliceId, null, "Doc archi", false, false);
        interlocuteurRepository.save(Interlocuteur.builder().userId(aliceId).hostId(hostId).displayName("Zahi Ben")
                .normalizedName("zahi ben").variants("zahi\nz. ben").role("RSSI")
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());
        messageRepository.save(AtelierMessage.builder().workspaceId(forge.getId()).userId(aliceId).role("ASSISTANT")
                .content(REPLY).build());
    }

    @Test
    @DisplayName("clients → sujets (terminal du poste d'abord, Teams exclu) → chantier et étape ; personnes ; à envoyer")
    void universeOfAlice() throws Exception {
        guided(forge, "Bascule DNS");

        String body = perform(aliceToken, get("/api/jarvis/universe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clients.length()").value(2))
                .andExpect(jsonPath("$.clients[0].name").value("CAGIP"))
                .andExpect(jsonPath("$.clients[0].virtual").value(false))
                .andExpect(jsonPath("$.clients[0].missionStatus").exists())
                .andExpect(jsonPath("$.clients[0].subjects.length()").value(2))
                .andExpect(jsonPath("$.clients[0].subjects[0].name").value(JarvisUniverseService.HOST_TERMINAL_NAME))
                .andExpect(jsonPath("$.clients[0].subjects[0].hostTerminal").value(true))
                .andExpect(jsonPath("$.clients[0].subjects[1].name").value("Migration Forge"))
                .andExpect(jsonPath("$.clients[0].subjects[1].chantiers.length()").value(1))
                .andExpect(jsonPath("$.clients[0].subjects[1].chantiers[0].number").value(1))
                .andExpect(jsonPath("$.clients[0].subjects[1].chantiers[0].title").value("Bascule DNS"))
                .andExpect(jsonPath("$.clients[0].people[0].name").value("Zahi Ben"))
                .andExpect(jsonPath("$.clients[0].people[0].variants[0]").value("zahi"))
                .andExpect(jsonPath("$.clients[0].toSend[0]").value("Zahi"))
                .andExpect(jsonPath("$.clients[1].name").value(JarvisUniverseService.HOSTED_NAME))
                .andExpect(jsonPath("$.clients[1].virtual").value(true))
                .andExpect(jsonPath("$.clients[1].subjects[0].name").value("Doc archi"))
                .andExpect(jsonPath("$.toSendCount").value(1))
                .andReturn().getResponse().getContentAsString();

        // Aucun contenu : ni corps de mail, ni objet, ni identifiant de compte.
        assertThat(body).doesNotContain("CORPS-SECRET-DU-MAIL").doesNotContain("Compte forge")
                .doesNotContain(aliceId.toString()).doesNotContain("teams");
    }

    @Test
    @DisplayName("isolation : Bob ne voit rien de l'univers d'Alice")
    void isolation() throws Exception {
        String body = perform(bobToken, get("/api/jarvis/universe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clients.length()").value(0))
                .andExpect(jsonPath("$.toSendCount").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("CAGIP").doesNotContain("Zahi").doesNotContain(hostId.toString());
    }

    @Test
    @DisplayName("bornes : 13 postes ⇒ 12 clients")
    void boundedClients() throws Exception {
        for (int i = 0; i < 13; i++) {
            seedHost(bobId, "poste-" + i);
        }
        perform(bobToken, get("/api/jarvis/universe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clients.length()").value(JarvisUniverseService.MAX_CLIENTS));
    }

    @Test
    @DisplayName("sans jeton : 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/api/jarvis/universe").contextPath("/api")).andExpect(status().isUnauthorized());
    }

    // ---- outillage ----

    private void guided(Workspace w, String title) throws Exception {
        perform(aliceToken, put("/api/workspaces/" + w.getId() + "/journey/mode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"GUIDE\",\"title\":\"" + title + "\"}"))
                .andExpect(status().isOk());
    }

    private RunnerHost seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev").os("linux")
                .shell("posix").elevated(false).lastSeenAt(OffsetDateTime.now()).build());
    }

    private Workspace seedWorkspace(UUID userId, UUID host, String name, boolean hostTerminal, boolean teams) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name(name).hostId(host)
                .projectPath(name).hostTerminal(hostTerminal).teamsTerminal(teams)
                .executionTarget(host == null ? WorkspaceExecutionTarget.SANDBOX : WorkspaceExecutionTarget.RUNNER)
                .build());
    }

    private ResultActions perform(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.contextPath("/api").header("Authorization", "Bearer " + token));
    }

    /** ADMIN : la Forge et le runner sont ouverts d'office (le droit d'accès n'est pas l'objet de ce test). */
    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }
}
