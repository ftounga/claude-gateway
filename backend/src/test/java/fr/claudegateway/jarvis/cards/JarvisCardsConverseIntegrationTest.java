package fr.claudegateway.jarvis.cards;

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
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionKind;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-248 / SF-248-02 — l'événement {@code cards} de {@code /api/jarvis/converse} : après la première phrase (D10), seules
 * les fiches des lignes lues sous le compte (isolation à deux utilisateurs), rien en présentation, préfixe intact.
 */
@SpringBootTest(properties = "app.jarvis.fast-brain.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCardsConverseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private TerminalActionRepository actions;
    @Autowired private JwtService jwtService;
    @MockitoBean private AiAgentProvider agentProvider;

    private String aliceToken;
    private String bobToken;
    private UUID aliceWs;
    private UUID bobWs;
    private UUID aliceAction;
    private UUID bobAction;
    private final List<String> systems = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        User alice = seedUser("cards-alice-" + UUID.randomUUID() + "@ex.com");
        User bob = seedUser("cards-bob-" + UUID.randomUUID() + "@ex.com");
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        aliceWs = workspace(alice, "Edenred");
        bobWs = workspace(bob, "Autre");
        aliceAction = action(alice.getId(), aliceWs, "Obtenir l'accès VPN de Rémi");
        bobAction = action(bob.getId(), bobWs, "Attente privée de Bob");
        when(agentProvider.nextTurn(any(AgentTurnRequest.class), any())).thenAnswer(invocation -> {
            AgentTurnRequest call = invocation.getArgument(0);
            systems.add(call.system());
            prompts.add(String.valueOf(call.messages()) + call.tools());
            AiAgentProvider.AgentTextListener listener = invocation.getArgument(1);
            listener.onTextDelta("Une attente est ouverte, Monsieur.");
            return new AgentTurn("Une attente est ouverte, Monsieur.", List.of(), true, 100, 8);
        });
    }

    private UUID workspace(User owner, String name) {
        UUID hostId = hostRepository.save(RunnerHost.builder().userId(owner.getId()).name("poste-" + name)
                .rootName("dev").os("linux").shell("posix").elevated(false).lastSeenAt(OffsetDateTime.now()).build())
                .getId();
        return workspaceRepository.save(Workspace.builder().userId(owner.getId()).name(name).hostId(hostId)
                .projectPath(name.toLowerCase()).executionTarget(WorkspaceExecutionTarget.RUNNER).build()).getId();
    }

    private UUID action(UUID userId, UUID ws, String description) {
        OffsetDateTime at = OffsetDateTime.now().minusHours(2);
        return actions.save(TerminalAction.builder().userId(userId).workspaceId(ws).description(description)
                .kind(TerminalActionKind.ACTION).status(TerminalActionStatus.A_FAIRE).person("Rémi")
                .createdAt(at).updatedAt(at).build()).getId();
    }

    private String converse(String token, UUID ws, boolean presentation) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/jarvis/converse").contextPath("/api")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"" + ws + "\",\"text\":\"Merci Jarvis\""
                                + (presentation ? ",\"presentation\":true" : "") + "}"))
                .andExpect(request().asyncStarted()).andReturn();
        return mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    @Test
    @DisplayName("D10 + isolation : « cards » part après la première phrase, avec l'attente d'Alice seule")
    void cardsAfterTheFirstSentence() throws Exception {
        String body = converse(aliceToken, aliceWs, false);
        assertThat(body).contains("event:cards").contains("fait:attente:" + aliceAction)
                .doesNotContain(bobAction.toString()).doesNotContain("Attente privée de Bob");
        assertThat(body.indexOf("event:text")).isLessThan(body.indexOf("event:cards"));
        assertThat(body.indexOf("event:cards")).isLessThan(body.indexOf("event:done"));
        assertThat(body).contains("\"targeted\":false").contains("\"type\":\"ATTENTE\"");

        String bobs = converse(bobToken, bobWs, false);
        assertThat(bobs).contains("fait:attente:" + bobAction).doesNotContain(aliceAction.toString());
    }

    @Test
    @DisplayName("présentation : aucun événement « cards » (§4.6)")
    void nothingInPresentation() throws Exception {
        String body = converse(aliceToken, aliceWs, true);
        assertThat(body).contains("event:done").doesNotContain("event:cards");
    }

    @Autowired private fr.claudegateway.jarvis.fastbrain.JarvisFastBrainController controller;

    @Test
    @DisplayName("K6/K7 : consigne et message identiques à l'octet pancartes allumées ou éteintes ; éteintes, aucun événement")
    void promptUnchangedAndKillSwitch() throws Exception {
        String on = converse(aliceToken, aliceWs, false);
        controller.setCards(new JarvisCardsProperties(false));
        try {
            String off = converse(aliceToken, aliceWs, false);
            assertThat(on).contains("event:cards");
            assertThat(off).contains("event:done").doesNotContain("event:cards");
        } finally {
            controller.setCards(JarvisCardsProperties.defaults());
        }
        assertThat(systems).hasSize(2);
        assertThat(systems.get(1)).isEqualTo(systems.get(0));
        assertThat(prompts).allSatisfy(p -> assertThat(p).doesNotContain("fait:").doesNotContain("pancarte"));
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build());
    }
}
