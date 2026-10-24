package fr.claudegateway.atelier.sends;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * « Marquer envoyé » de bout en bout (F-211 / SF-211-01) : l'envoi noté, relu, annulé ; l'attente liée
 * qui passe « Demandé » puis revient ; et <b>le terminal d'Alice introuvable pour Bob</b>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessageBlockSendApiIntegrationTest {

    private static final String REPLY = "Voici le mail :\n\n```mail attente=acces-forge\nÀ : Zahi\n"
            + "Objet : Compte forge\n\nBonjour Zahi,\npeux-tu m'ouvrir un compte ?\n```\n\n"
            + "Et le message :\n```message\nSalut l'équipe\n```\n";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private TerminalActionRepository actionRepository;
    @Autowired private AtelierMessageRepository messageRepository;
    @Autowired private MessageBlockSendRepository sendRepository;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private UUID aliceId;
    private String workspaceId;
    private UUID messageId;

    @BeforeEach
    void setUp() throws Exception {
        sendRepository.deleteAll();
        messageRepository.deleteAll();
        actionRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        aliceToken = tokenFor("alice-sends@ex.com");
        aliceId = userRepository.findByEmail("alice-sends@ex.com").orElseThrow().getId();
        bobToken = tokenFor("bob-sends@ex.com");
        workspaceId = createWorkspace(aliceToken, "Connecteur AGENOR");
        messageId = agentMessage(UUID.fromString(workspaceId), REPLY);
    }

    @Test
    @DisplayName("marquer, relire, annuler : l'envoi suit le geste, objet et destinataire lus dans le message")
    void markListCancel() throws Exception {
        perform(aliceToken, post(sendUrl(messageId, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockIndex").value(1))
                .andExpect(jsonPath("$.kind").value("MESSAGE"))
                .andExpect(jsonPath("$.sentAt").isNotEmpty());

        perform(aliceToken, get(listUrl()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].messageId").value(messageId.toString()))
                .andExpect(jsonPath("$[0].blockIndex").value(1));

        // Marquer deux fois : sans effet.
        perform(aliceToken, post(sendUrl(messageId, 1))).andExpect(status().isOk());
        assertThat(sendRepository.count()).isEqualTo(1);

        perform(aliceToken, delete(sendUrl(messageId, 1))).andExpect(status().isNoContent());
        perform(aliceToken, get(listUrl())).andExpect(jsonPath("$.length()").value(0));
        // Annuler deux fois : sans effet.
        perform(aliceToken, delete(sendUrl(messageId, 1))).andExpect(status().isNoContent());

        // Re-marquer réactive l'envoi.
        perform(aliceToken, post(sendUrl(messageId, 1))).andExpect(status().isOk());
        perform(aliceToken, get(listUrl())).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("le mail qui sert l'attente la fait passer « Demandé » à Zahi par mail ; Annuler la remet À faire")
    void theLinkedAttenteMovesAndComesBack() throws Exception {
        UUID actionId = attente("acces-forge");

        perform(aliceToken, post(sendUrl(messageId, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Compte forge"))
                .andExpect(jsonPath("$.recipient").value("Zahi"))
                .andExpect(jsonPath("$.attenteKey").value("acces-forge"));

        TerminalAction requested = actionRepository.findById(actionId).orElseThrow();
        assertThat(requested.getStatus()).isEqualTo(TerminalActionStatus.DEMANDE);
        assertThat(requested.getRequestedTo()).isEqualTo("Zahi");
        assertThat(requested.getChannel()).isEqualTo("mail");
        assertThat(requested.getRequestedAt()).isNotNull();

        perform(aliceToken, delete(sendUrl(messageId, 0))).andExpect(status().isNoContent());

        TerminalAction back = actionRepository.findById(actionId).orElseThrow();
        assertThat(back.getStatus()).isEqualTo(TerminalActionStatus.A_FAIRE);
        assertThat(back.getRequestedAt()).isNull();
        assertThat(back.getRequestedTo()).isNull();
        assertThat(back.getChannel()).isNull();
    }

    @Test
    @DisplayName("une attente qui a bougé entre-temps n'est pas restaurée par Annuler")
    void anAttenteThatMovedIsLeftAlone() throws Exception {
        UUID actionId = attente("acces-forge");
        perform(aliceToken, post(sendUrl(messageId, 0))).andExpect(status().isOk());

        // L'utilisateur la ferme entre-temps.
        perform(aliceToken, post("/api/workspaces/" + workspaceId + "/actions/" + actionId + "/close")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());

        perform(aliceToken, delete(sendUrl(messageId, 0))).andExpect(status().isNoContent());

        assertThat(actionRepository.findById(actionId).orElseThrow().getStatus())
                .isEqualTo(TerminalActionStatus.FAIT);
    }

    @Test
    @DisplayName("une clé d'attente inconnue n'empêche pas de marquer l'envoi")
    void unknownKeyStillMarks() throws Exception {
        perform(aliceToken, post(sendUrl(messageId, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attenteKey").doesNotExist());
    }

    @Test
    @DisplayName("un rang hors du message → 400 ; un message de l'utilisateur ou d'un autre terminal → 404")
    void invalidTargets() throws Exception {
        perform(aliceToken, post(sendUrl(messageId, 2))).andExpect(status().isBadRequest());
        perform(aliceToken, post(sendUrl(messageId, -1))).andExpect(status().isBadRequest());

        AtelierMessage userMessage = messageRepository.save(AtelierMessage.builder()
                .workspaceId(UUID.fromString(workspaceId)).userId(aliceId).role("USER").content(REPLY).build());
        perform(aliceToken, post(sendUrl(userMessage.getId(), 0))).andExpect(status().isNotFound());

        String other = createWorkspace(aliceToken, "Autre");
        UUID otherMessage = agentMessage(UUID.fromString(other), REPLY);
        perform(aliceToken, post(sendUrl(otherMessage, 0))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("isolation : Bob ne voit ni ne marque rien sur le terminal d'Alice (404)")
    void bobGetsNotFound() throws Exception {
        perform(aliceToken, post(sendUrl(messageId, 0))).andExpect(status().isOk());

        perform(bobToken, post(sendUrl(messageId, 1))).andExpect(status().isNotFound());
        perform(bobToken, delete(sendUrl(messageId, 0))).andExpect(status().isNotFound());
        perform(bobToken, get(listUrl())).andExpect(status().isNotFound());

        perform(aliceToken, get(listUrl())).andExpect(jsonPath("$.length()").value(1));
    }

    // ---- outillage ----

    private String listUrl() {
        return "/api/workspaces/" + workspaceId + "/messages/sends";
    }

    private String sendUrl(UUID message, int index) {
        return "/api/workspaces/" + workspaceId + "/messages/" + message + "/sends/" + index;
    }

    private UUID agentMessage(UUID workspace, String content) {
        return messageRepository.save(AtelierMessage.builder().workspaceId(workspace).userId(aliceId)
                .role("ASSISTANT").content(content).build()).getId();
    }

    private UUID attente(String key) throws Exception {
        String body = perform(aliceToken, post("/api/workspaces/" + workspaceId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Demander le compte forge\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        TerminalAction action = actionRepository.findById(id).orElseThrow();
        action.setDedupKey(key);
        action.setUpdatedAt(OffsetDateTime.now());
        actionRepository.save(action);
        return id;
    }

    private String tokenFor(String email) {
        User user = userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        subscriptionRepository.save(Subscription.builder().userId(user.getId())
                .planCode(PlanCode.GOLD).status(SubscriptionStatus.ACTIVE).build());
        return jwtService.generateToken(user);
    }

    private String createWorkspace(String token, String name) throws Exception {
        String body = mockMvc.perform(multipart("/api/workspaces").file(zip()).contextPath("/api")
                        .param("name", name)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions perform(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.contextPath("/api").header("Authorization", "Bearer " + token));
    }

    private static MockMultipartFile zip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("README.md"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return new MockMultipartFile("file", "project.zip", "application/zip", out.toByteArray());
    }
}
