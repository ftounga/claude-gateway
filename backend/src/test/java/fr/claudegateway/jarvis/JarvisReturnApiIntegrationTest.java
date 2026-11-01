package fr.claudegateway.jarvis;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
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
 * F-231 / SF-231-06 — <b>de retour au terminal</b> : l'encart « Pendant ton absence, via Jarvis », la marque dans
 * l'historique et le point de situation, le compteur « À envoyer (n) » du tableau du client ; <b>rien chez Bob</b>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisReturnApiIntegrationTest {

    private static final String REPLY = "🎙 Message pour Zahi prêt, à envoyer depuis le terminal.\n\n"
            + "```mail\nÀ : Zahi\nObjet : Compte forge\n\nBonjour Zahi\n```\n";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private AtelierMessageRepository messageRepository;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private UUID aliceId;
    private UUID hostId;
    private UUID workspaceId;
    private UUID replyId;

    @BeforeEach
    void setUp() {
        User alice = seedUser("jarvis-return-alice-" + UUID.randomUUID() + "@ex.com");
        User bob = seedUser("jarvis-return-bob-" + UUID.randomUUID() + "@ex.com");
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        hostId = hostRepository.save(RunnerHost.builder().userId(aliceId).name("cagip").rootName("dev").os("linux")
                .shell("posix").elevated(false).lastSeenAt(OffsetDateTime.now()).build()).getId();
        workspaceId = workspaceRepository.save(Workspace.builder().userId(aliceId).name("Connecteur AGENOR")
                .hostId(hostId).projectPath("agenor").executionTarget(WorkspaceExecutionTarget.RUNNER).build()).getId();

        // Un tour tapé (sans marque), puis un tour né à la voix qui prépare un mail.
        message("USER", "Où en est le connecteur ?", null);
        message("ASSISTANT", "Il compile.", null);
        message("USER", "Prépare le mail pour Zahi", AtelierMessage.VIA_JARVIS);
        replyId = message("ASSISTANT", REPLY, AtelierMessage.VIA_JARVIS);
    }

    @Test
    @DisplayName("l'encart liste la demande dite à la voix, la phrase de la réponse et le message à envoyer")
    void absenceListsVoiceTurnsAndMessagesToSend() throws Exception {
        String since = OffsetDateTime.now().minusHours(2).toString();
        perform(aliceToken, get("/api/workspaces/" + workspaceId + "/jarvis/absence").param("since", since))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns.length()").value(1))
                .andExpect(jsonPath("$.turns[0].request").value("Prépare le mail pour Zahi"))
                .andExpect(jsonPath("$.turns[0].reply").value("Message pour Zahi prêt, à envoyer depuis le terminal."))
                .andExpect(jsonPath("$.toSend.length()").value(1))
                .andExpect(jsonPath("$.toSend[0].recipient").value("Zahi"))
                .andExpect(jsonPath("$.toSend[0].sentAt").doesNotExist())
                .andExpect(jsonPath("$.toSend[0].viaJarvis").value(true));

        // Après la dernière visite : rien à montrer.
        perform(aliceToken, get("/api/workspaces/" + workspaceId + "/jarvis/absence")
                .param("since", OffsetDateTime.now().plusMinutes(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns.length()").value(0));
    }

    @Test
    @DisplayName("la marque « via Jarvis » voyage dans l'historique et dans le point de situation")
    void markInHistoryAndSituation() throws Exception {
        perform(aliceToken, get("/api/workspaces/" + workspaceId + "/chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].via").doesNotExist())
                .andExpect(jsonPath("$[2].via").value("JARVIS"))
                .andExpect(jsonPath("$[3].via").value("JARVIS"));
        perform(aliceToken, get("/api/workspaces/" + workspaceId + "/situation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchange.viaJarvis").value(true))
                .andExpect(jsonPath("$.envois[0].viaJarvis").value(true));
    }

    @Test
    @DisplayName("« À envoyer (n) » : compte les encarts non marqués envoyés du poste ; marqué envoyé ⇒ il sort")
    void toSendCounter() throws Exception {
        perform(aliceToken, get("/api/jarvis/to-send").param("hostId", hostId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].workspaceName").value("Connecteur AGENOR"))
                .andExpect(jsonPath("$.items[0].subject").value("Compte forge"));

        perform(aliceToken, post("/api/workspaces/" + workspaceId + "/messages/" + replyId + "/sends/0"))
                .andExpect(status().isOk());

        perform(aliceToken, get("/api/jarvis/to-send").param("hostId", hostId.toString()))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("isolation : le terminal d'Alice est introuvable pour Bob ; son compteur est vide")
    void isolation() throws Exception {
        perform(bobToken, get("/api/workspaces/" + workspaceId + "/jarvis/absence"))
                .andExpect(status().isNotFound());
        perform(bobToken, get("/api/jarvis/to-send").param("hostId", hostId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    // ---- outillage ----

    private UUID message(String role, String content, String via) {
        try {
            Thread.sleep(5); // des instants distincts : l'ordre du fil se lit sur created_at
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return messageRepository.save(AtelierMessage.builder().workspaceId(workspaceId).userId(aliceId).role(role)
                .content(content).via(via).build()).getId();
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
