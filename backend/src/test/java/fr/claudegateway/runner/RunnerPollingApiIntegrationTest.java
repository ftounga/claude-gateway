package fr.claudegateway.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.channel.LongPollingRunnerOutbound;
import fr.claudegateway.runner.channel.RunnerPollingSessions;
import fr.claudegateway.runner.channel.RunnerRegistry;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration du <b>repli de transport</b> (F-38 / SF-38-09) : les trois endpoints de
 * long-polling servis par la chaîne dédiée {@code /runner/**}.
 *
 * <p>Trois choses doivent tenir : le jeton runner authentifie <b>ces endpoints seulement</b> (jamais
 * un endpoint utilisateur, D9), les trames circulent <b>verbatim</b> (aucun type nouveau), et un
 * runner d'un projet ne voit jamais les trames d'un autre.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerPollingApiIntegrationTest {

    private static final String POLL = "/api/runner/poll";
    private static final String SEND = "/api/runner/send";
    private static final String DISCONNECT = "/api/runner/disconnect";
    private static final String HEADER = "X-Runner-Token";

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
    private RunnerPollingSessions sessions;
    @Autowired
    private RunnerRegistry registry;
    @Autowired
    private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User owner;
    private String ownerJwt;
    private Workspace workspace;
    private RunnerTokenService.IssuedToken issued;

    private User other;
    private Workspace otherWorkspace;
    private RunnerTokenService.IssuedToken otherIssued;

    @BeforeEach
    void setUp() {
        runnerTokenRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

        owner = seedUser("poll-owner@example.com");
        ownerJwt = jwtService.generateToken(owner);
        workspace = seedWorkspace(owner.getId());
        issued = tokenService.issue(owner.getId(), workspace.getId(), "poste-1");

        other = seedUser("poll-other@example.com");
        otherWorkspace = seedWorkspace(other.getId());
        otherIssued = tokenService.issue(other.getId(), otherWorkspace.getId(), "poste-2");
    }

    @AfterEach
    void tearDown() {
        // Le registre et la carte des canaux sont des singletons partagés entre tests.
        sessions.close(identity(issued));
        sessions.close(identity(otherIssued));
    }

    private RunnerIdentity identity(RunnerTokenService.IssuedToken token) {
        return new RunnerIdentity(token.token().getId(), token.token().getUserId(),
                token.token().getWorkspaceId());
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder()
                .email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private Workspace seedWorkspace(UUID userId) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name("Projet").build());
    }

    // ---------------------------------------------------------------- 401 générique

    @Test
    void pollWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post(POLL).contextPath("/api"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("runner_unauthorized"));
    }

    @Test
    void pollWithRevokedTokenIsUnauthorizedTheSameWay() throws Exception {
        tokenService.revoke(owner.getId(), workspace.getId(), issued.token().getId());

        // Aucun oracle : « jeton absent » et « jeton révoqué » se répondent à l'identique.
        mockMvc.perform(post(POLL).contextPath("/api").header(HEADER, issued.clearToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("runner_unauthorized"));
    }

    @Test
    void sendAndDisconnectAlsoRefuseAnUnknownToken() throws Exception {
        mockMvc.perform(post(SEND).contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"heartbeat\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(DISCONNECT).contextPath("/api").header(HEADER, "jeton-bidon"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- cas nominal

    @Test
    void firstPollOpensTheChannelAndMakesTheRunnerVisibleAsConnected() throws Exception {
        mockMvc.perform(post(POLL).contextPath("/api")
                        .header(HEADER, issued.clearToken()).param("waitMs", "0"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"frames\":[]}"));

        mockMvc.perform(get("/api/workspaces/" + workspace.getId() + "/runner/status")
                        .contextPath("/api").header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true));
    }

    @Test
    void aQueuedFrameIsReturnedVerbatimByTheNextPoll() throws Exception {
        LongPollingRunnerOutbound channel = sessions.open(identity(issued));
        String frame = "{\"type\":\"tool_call\",\"id\":\"toolu_1\",\"tool\":\"read_file\","
                + "\"input\":{\"path\":\"src/a.ts\"},\"timeoutMs\":30000}";
        channel.send(frame);

        String body = mockMvc.perform(post(POLL).contextPath("/api")
                        .header(HEADER, issued.clearToken()).param("waitMs", "0"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode returned = objectMapper.readTree(body).path("frames").get(0);
        assertThat(returned.path("type").asText()).isEqualTo("tool_call");
        assertThat(returned.path("id").asText()).isEqualTo("toolu_1");
        assertThat(returned.path("tool").asText()).isEqualTo("read_file");
        assertThat(returned.path("input").path("path").asText()).isEqualTo("src/a.ts");
        assertThat(returned.path("timeoutMs").asLong()).isEqualTo(30_000L);
    }

    @Test
    void aPostedHeartbeatIsAcknowledgedOnTheNextPoll() throws Exception {
        sessions.open(identity(issued));

        mockMvc.perform(post(SEND).contextPath("/api").header(HEADER, issued.clearToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"heartbeat\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(POLL).contextPath("/api")
                        .header(HEADER, issued.clearToken()).param("waitMs", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frames[0].type").value("heartbeat_ack"));
    }

    @Test
    void anUnreadableOrUnknownFrameIsSwallowedWithoutBreakingTheChannel() throws Exception {
        LongPollingRunnerOutbound channel = sessions.open(identity(issued));

        mockMvc.perform(post(SEND).contextPath("/api").header(HEADER, issued.clearToken())
                        .contentType(MediaType.APPLICATION_JSON).content("ceci n'est pas du json"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(SEND).contextPath("/api").header(HEADER, issued.clearToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frames\":[{\"type\":\"venu_du_futur\",\"x\":1}]}"))
                .andExpect(status().isNoContent());

        // Compatibilité ascendante (contrat §0) : rien n'est fermé, rien n'est rejeté.
        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void disconnectClosesTheChannelAndTheRunnerBecomesDisconnected() throws Exception {
        sessions.open(identity(issued));

        mockMvc.perform(post(DISCONNECT).contextPath("/api").header(HEADER, issued.clearToken()))
                .andExpect(status().isNoContent());

        assertThat(registry.findLocal(workspace.getId())).isEmpty();
        mockMvc.perform(get("/api/workspaces/" + workspace.getId() + "/runner/status")
                        .contextPath("/api").header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void aPollInFlightWhenTheChannelIsCutGetsAConflict() throws Exception {
        LongPollingRunnerOutbound channel = sessions.open(identity(issued));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> polling = executor.submit(() -> mockMvc.perform(post(POLL)
                            .contextPath("/api").header(HEADER, issued.clearToken())
                            .param("waitMs", "10000"))
                    .andReturn().getResponse().getStatus());
            Thread.sleep(200); // le poll est parti et attend

            channel.close(); // coupe-circuit, révocation ou balayage d'inactivité

            // Le runner doit apprendre que la liaison est morte, pas attendre 10 s dans le vide puis
            // repoller indéfiniment.
            assertThat(polling.get(10, TimeUnit.SECONDS)).isEqualTo(409);
        } finally {
            executor.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- isolation

    @Test
    void aPollNeverReturnsAnotherWorkspacesFrames() throws Exception {
        LongPollingRunnerOutbound mine = sessions.open(identity(issued));
        LongPollingRunnerOutbound theirs = sessions.open(identity(otherIssued));
        mine.send("{\"type\":\"tool_call\",\"id\":\"a\"}");
        theirs.send("{\"type\":\"tool_call\",\"id\":\"b\"}");

        String body = mockMvc.perform(post(POLL).contextPath("/api")
                        .header(HEADER, otherIssued.clearToken()).param("waitMs", "0"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode frames = objectMapper.readTree(body).path("frames");
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).path("id").asText()).isEqualTo("b");
    }

    // ---------------------------------------------------------------- non-régression sécurité

    @Test
    void aRunnerTokenNeverAuthenticatesAUserEndpoint() throws Exception {
        // Le jeton runner n'est jamais posé dans le SecurityContext (D9) : il ne peut pas ouvrir
        // /me, ni en X-Runner-Token, ni en Bearer.
        mockMvc.perform(get("/api/me").contextPath("/api").header(HEADER, issued.clearToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me").contextPath("/api")
                        .header("Authorization", "Bearer " + issued.clearToken()))
                .andExpect(status().isUnauthorized());
        // Et la chaîne principale reste inchangée avec un vrai JWT.
        mockMvc.perform(get("/api/me").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isOk());
    }

    @Test
    void anyOtherRunnerUrlIsStillDenied() throws Exception {
        mockMvc.perform(post("/api/runner/inconnu").contextPath("/api")
                        .header(HEADER, issued.clearToken()))
                .andExpect(status().isForbidden());
    }
}
