package fr.claudegateway.jarvis.fastbrain;

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

/** F-235 / SF-235-01 — {@code POST /api/jarvis/converse} allumé : réponse en flux, isolation, validation. */
@SpringBootTest(properties = "app.jarvis.fast-brain.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisFastBrainApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private JwtService jwtService;
    @Autowired private fr.claudegateway.atelier.AtelierMessageRepository messageRepository;
    @Autowired private fr.claudegateway.jarvis.thread.JarvisThreadService threads;
    @MockitoBean private AiAgentProvider agentProvider;

    private UUID aliceId;
    private UUID bobId;
    private String aliceToken;
    private String bobToken;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        User alice = seedUser("fast-alice-" + UUID.randomUUID() + "@ex.com");
        User bob = seedUser("fast-bob-" + UUID.randomUUID() + "@ex.com");
        aliceId = alice.getId();
        bobId = bob.getId();
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        UUID hostId = hostRepository.save(RunnerHost.builder().userId(alice.getId()).name("cagip").rootName("dev")
                .os("linux").shell("posix").elevated(false).lastSeenAt(OffsetDateTime.now()).build()).getId();
        workspaceId = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("Edenred")
                .hostId(hostId).projectPath("edenred").executionTarget(WorkspaceExecutionTarget.RUNNER).build())
                .getId();
        when(agentProvider.nextTurn(any(AgentTurnRequest.class), any())).thenAnswer(invocation -> {
            AiAgentProvider.AgentTextListener listener = invocation.getArgument(1);
            listener.onTextDelta("Avec plaisir, Monsieur.");
            return new AgentTurn("Avec plaisir, Monsieur.", List.of(), true, 100, 8);
        });
    }

    @Test
    @DisplayName("allumé : la réponse rapide arrive en flux, puis « done » avec le cerveau FAST")
    void answersInTheStream() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/jarvis/converse").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"" + workspaceId + "\",\"text\":\"Merci Jarvis\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(body).contains("event:text").contains("Avec plaisir, Monsieur.")
                .contains("event:done").contains("\"brain\":\"FAST\"");
    }

    @Test
    @DisplayName("F-245 / SF-245-02 critères 1 et 6 : un « bonjour » avec un sujet en vue ne s'écrit que dans le fil "
            + "Jarvis d'Alice — rien dans le sujet, rien chez Bob")
    void aGreetingIsWrittenInTheJarvisThreadOnly() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/jarvis/converse").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"" + workspaceId + "\",\"text\":\"Bonjour Jarvis\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());

        assertThat(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, aliceId)).isEmpty();
        UUID thread = threads.find(aliceId).orElseThrow().getId();
        assertThat(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(thread, aliceId))
                .extracting(fr.claudegateway.atelier.AtelierMessage::getContent)
                .hasSize(2).first().isEqualTo("Bonjour Jarvis");
        assertThat(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(thread, bobId)).isEmpty();
        assertThat(threads.find(bobId)).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions verdict(String token, String json) throws Exception {
        return mockMvc.perform(post("/api/jarvis/converse/verdict").contextPath("/api")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String provisional(UUID speculation) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/jarvis/converse").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"" + workspaceId + "\",\"text\":\"bonjour jarvis\","
                                + "\"speculationId\":\"" + speculation + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    @Test
    @DisplayName("F-252 / SF-252-02 — adoptée : la réponse finit normalement, tracée avec le texte de référence")
    void aProvisionalAnswerAdoptedIsWrittenWithTheReference() throws Exception {
        UUID speculation = UUID.randomUUID();
        verdict(aliceToken, "{\"speculationId\":\"" + speculation + "\",\"adopted\":true,"
                + "\"text\":\"Bonjour Jarvis.\"}").andExpect(status().isNoContent());
        String body = provisional(speculation);
        assertThat(body).contains("event:text").contains("event:done").doesNotContain("event:dropped");
        UUID thread = threads.find(aliceId).orElseThrow().getId();
        assertThat(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(thread, aliceId))
                .extracting(fr.claudegateway.atelier.AtelierMessage::getContent).first().isEqualTo("Bonjour Jarvis.");
    }

    @Test
    @DisplayName("F-252 / SF-252-02 — jetée : événement dropped, aucune trace ; le verdict d'un autre compte ne compte pas")
    void aProvisionalAnswerRejectedLeavesNoTrace() throws Exception {
        UUID speculation = UUID.randomUUID();
        verdict(bobToken, "{\"speculationId\":\"" + speculation + "\",\"adopted\":true,\"text\":\"Autre\"}")
                .andExpect(status().isNoContent());
        verdict(aliceToken, "{\"speculationId\":\"" + speculation + "\",\"adopted\":false}")
                .andExpect(status().isNoContent());
        String body = provisional(speculation);
        assertThat(body).contains("event:dropped").doesNotContain("event:done");
        assertThat(threads.find(aliceId)).isEmpty();
    }

    @Test
    @DisplayName("F-252 / SF-252-02 — verdict invalide : 400")
    void anInvalidVerdictIsRefused() throws Exception {
        UUID speculation = UUID.randomUUID();
        verdict(aliceToken, "{\"adopted\":true,\"text\":\"x\"}").andExpect(status().isBadRequest());
        verdict(aliceToken, "{\"speculationId\":\"" + speculation + "\",\"adopted\":true}")
                .andExpect(status().isBadRequest());
        verdict(aliceToken, "{\"speculationId\":\"" + speculation + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("isolation : le sujet d'Alice est introuvable pour Bob (404), rien ne part au fournisseur")
    void anotherAccountsSubjectIsNotFound() throws Exception {
        mockMvc.perform(post("/api/jarvis/converse").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"" + workspaceId + "\",\"text\":\"Bonjour\"}"))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(agentProvider);
    }

    @Test
    @DisplayName("validation : parole vide ⇒ 400 ; sans jeton ⇒ refus")
    void validation() throws Exception {
        mockMvc.perform(post("/api/jarvis/converse").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"" + workspaceId + "\",\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/jarvis/converse").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"" + workspaceId + "\",\"text\":\"Bonjour\"}"))
                .andExpect(status().is4xxClientError());
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }
}
