package fr.claudegateway.jarvis.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-255 / SF-255-01 — la requête d'un tour reçue par le cerveau rapide est notée au journal d'écoute ({@code
 * srv_converse}), sous le compte de l'appelant seulement, avec le sujet de l'écran ; rien sans identifiant de tour ni sur
 * une requête refusée.
 */
@SpringBootTest(properties = "app.jarvis.fast-brain.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisTurnReceivedApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisListenTraceRepository traces;
    @MockitoBean private AiAgentProvider agentProvider;

    private UUID aliceId;
    private UUID bobId;
    private String aliceToken;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        User alice = seedUser("turn-alice-" + UUID.randomUUID() + "@ex.com");
        User bob = seedUser("turn-bob-" + UUID.randomUUID() + "@ex.com");
        aliceId = alice.getId();
        bobId = bob.getId();
        aliceToken = jwtService.generateToken(alice);
        UUID hostId = hostRepository.save(RunnerHost.builder().userId(aliceId).name("cagip").rootName("dev")
                .os("linux").shell("posix").elevated(false).lastSeenAt(OffsetDateTime.now()).build()).getId();
        workspaceId = workspaceRepository.save(Workspace.builder().userId(aliceId).name("Edenred")
                .hostId(hostId).projectPath("edenred").executionTarget(WorkspaceExecutionTarget.RUNNER).build())
                .getId();
        when(agentProvider.nextTurn(any(AgentTurnRequest.class), any())).thenAnswer(invocation -> {
            AiAgentProvider.AgentTextListener listener = invocation.getArgument(1);
            listener.onTextDelta("Avec plaisir, Monsieur.");
            return new AgentTurn("Avec plaisir, Monsieur.", List.of(), true, 100, 8);
        });
    }

    private void converse(String json) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/jarvis/converse").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("avec un tour : srv_converse sous Alice, tour, page, sujet de l'écran, heure serveur ; rien chez Bob")
    void theReceivedTurnIsTraced() throws Exception {
        converse("{\"subjectId\":\"" + workspaceId + "\",\"text\":\"Merci Jarvis\",\"turnId\":\"tour-a1\","
                + "\"pageId\":\"page-a\"}");

        List<JarvisListenTraceEvent> mine = traces.findByUserIdOrderByCreatedAtAscSeqAsc(aliceId);
        assertThat(mine).singleElement().satisfies(e -> {
            assertThat(e.getEvent()).isEqualTo("srv_converse");
            assertThat(e.getTurnId()).isEqualTo("tour-a1");
            assertThat(e.getPageId()).isEqualTo("page-a");
            assertThat(e.getDetail()).isEqualTo("subject=" + workspaceId + " spec=0");
            assertThat(e.getOccurredAt()).isNotNull();
        });
        assertThat(traces.findByUserIdOrderByCreatedAtAscSeqAsc(bobId)).isEmpty();
    }

    @Test
    @DisplayName("sans tour (écran d'avant) : rien ; requête refusée (texte vide, sujet d'autrui) : rien")
    void nothingWithoutTurnOrWhenRefused() throws Exception {
        converse("{\"text\":\"Merci Jarvis\"}");
        mockMvc.perform(post("/api/jarvis/converse").contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"\",\"turnId\":\"tour-b\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/jarvis/converse").contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":\"" + UUID.randomUUID() + "\",\"text\":\"Bonjour\",\"turnId\":\"tour-c\"}"))
                .andExpect(status().isNotFound());

        assertThat(traces.findByUserIdOrderByCreatedAtAscSeqAsc(aliceId)).isEmpty();
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }
}
