package fr.claudegateway.jarvis.thread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-245 / SF-245-03 — <b>le chantier traite, le fil garde la ligne-lien</b> : une ligne {@code LINK} « en cours »
 * dans le fil Jarvis, terminée « fait » (ligne 🎙, réponse pointée) ou « échec » ; le sujet cible reste propre ; la
 * vue porte la cible ; isolation A/B.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisLinkIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private AtelierMessageRepository messageRepository;
    @Autowired private JarvisThreadService threads;
    @Autowired private JarvisLinkService links;
    @Autowired private JwtService jwtService;

    private UUID aliceId;
    private UUID bobId;
    private String aliceToken;
    private String bobToken;
    private Workspace helios;

    @BeforeEach
    void setUp() {
        User alice = seedUser("jarvis-link-alice-" + UUID.randomUUID() + "@ex.com");
        User bob = seedUser("jarvis-link-bob-" + UUID.randomUUID() + "@ex.com");
        aliceId = alice.getId();
        bobId = bob.getId();
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        helios = workspaceRepository.save(Workspace.builder().userId(aliceId).name("Helios")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());
    }

    @Test
    @DisplayName("critère 1 : la délégation crée une ligne « en cours », puis « fait » avec la ligne 🎙 et la réponse")
    void fullCycle() throws Exception {
        UUID linkId = links.open(aliceId, helios.getId(), 3, "relancer le déploiement");
        assertThat(linkId).isNotNull();
        UUID thread = threads.idOf(aliceId).orElseThrow();
        AtelierMessage running = messageRepository.findByIdAndUserId(linkId, aliceId).orElseThrow();
        assertThat(running.getWorkspaceId()).isEqualTo(thread);
        assertThat(running.getRole()).isEqualTo(AtelierMessage.ROLE_LINK);
        assertThat(running.getLinkStatus()).isEqualTo(AtelierMessage.LINK_RUNNING);
        assertThat(running.getVia()).isNull();
        assertThat(running.getContent()).isEqualTo("↗ Helios · chantier 3 — « relancer le déploiement » · en cours");

        // Le tour du chantier : la demande reformulée et la réponse, rien d'autre.
        messageRepository.save(AtelierMessage.builder().workspaceId(helios.getId()).userId(aliceId).role("USER")
                .chantierNumber(3).content("relance le déploiement").via(AtelierMessage.VIA_JARVIS).build());
        AtelierMessage reply = messageRepository.save(AtelierMessage.builder().workspaceId(helios.getId())
                .userId(aliceId).role("ASSISTANT").chantierNumber(3).via(AtelierMessage.VIA_JARVIS)
                .content("🎙 Le déploiement est relancé, Monsieur.\n\nDétail : pipeline 412.").build());
        links.finish(aliceId, linkId, reply, false);

        AtelierMessage done = messageRepository.findByIdAndUserId(linkId, aliceId).orElseThrow();
        assertThat(done.getLinkStatus()).isEqualTo(AtelierMessage.LINK_DONE);
        assertThat(done.getRefMessageId()).isEqualTo(reply.getId());
        assertThat(done.getContent()).startsWith("↗ Helios · chantier 3 — « relancer le déploiement » · fait\n🎙 ")
                .contains("Le déploiement est relancé").doesNotContain("pipeline 412");

        // Critère 2 : l'historique du chantier ne contient ni ligne-lien ni échange du fil Jarvis.
        List<AtelierMessage> subject = messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(
                helios.getId(), aliceId);
        assertThat(subject).hasSize(2).noneMatch(AtelierMessage::isLink);

        // La vue porte la cible (le toucher ouvre le chantier 3 à la réponse).
        perform(aliceToken, get("/api/workspaces/" + thread + "/chat")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("LINK"))
                .andExpect(jsonPath("$[0].link.workspaceId").value(helios.getId().toString()))
                .andExpect(jsonPath("$[0].link.chantier").value(3))
                .andExpect(jsonPath("$[0].link.messageId").value(reply.getId().toString()))
                .andExpect(jsonPath("$[0].link.status").value("FAIT"));
    }

    @Test
    @DisplayName("échec et interruption : la ligne passe à « échec » / « interrompu »")
    void failureAndInterruption() {
        UUID failed = links.open(aliceId, helios.getId(), null, "tickets bloqués");
        links.finish(aliceId, failed, null, false);
        AtelierMessage f = messageRepository.findByIdAndUserId(failed, aliceId).orElseThrow();
        assertThat(f.getLinkStatus()).isEqualTo(AtelierMessage.LINK_FAILED);
        assertThat(f.getRefMessageId()).isNull();
        assertThat(f.getContent()).isEqualTo("↗ Helios — « tickets bloqués » · échec");

        UUID cut = links.open(aliceId, helios.getId(), null, "logs");
        AtelierMessage reply = messageRepository.save(AtelierMessage.builder().workspaceId(helios.getId())
                .userId(aliceId).role("ASSISTANT").content("Interrompu.").build());
        links.finish(aliceId, cut, reply, true);
        AtelierMessage c = messageRepository.findByIdAndUserId(cut, aliceId).orElseThrow();
        assertThat(c.getLinkStatus()).isEqualTo(AtelierMessage.LINK_FAILED);
        assertThat(c.getRefMessageId()).isEqualTo(reply.getId());
        assertThat(c.getContent()).endsWith("· interrompu");
    }

    @Test
    @DisplayName("critère 5 et 6 : pas de lien vers le sujet d'autrui ; Bob ne termine ni ne lit la ligne d'Alice")
    void isolation() throws Exception {
        assertThat(links.open(bobId, helios.getId(), 3, "voler")).isNull();
        assertThat(threads.find(bobId)).isEmpty();

        UUID linkId = links.open(aliceId, helios.getId(), 3, "relancer");
        Workspace bobSubject = workspaceRepository.save(Workspace.builder().userId(bobId).name("Bob")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());
        AtelierMessage bobReply = messageRepository.save(AtelierMessage.builder().workspaceId(bobSubject.getId())
                .userId(bobId).role("ASSISTANT").content("🎙 piraté").build());
        links.finish(bobId, linkId, bobReply, false);
        assertThat(messageRepository.findByIdAndUserId(linkId, aliceId).orElseThrow().getLinkStatus())
                .isEqualTo(AtelierMessage.LINK_RUNNING);
        assertThat(messageRepository.findByIdAndUserId(linkId, bobId)).isEmpty();

        UUID aliceThread = threads.idOf(aliceId).orElseThrow();
        perform(bobToken, get("/api/workspaces/" + aliceThread + "/chat"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(403, 404));
    }

    @Test
    @DisplayName("aucune ligne vers le fil Jarvis lui-même")
    void noLinkToTheJarvisThread() {
        UUID thread = threads.open(aliceId).getId();
        assertThat(links.open(aliceId, thread, null, "tour d'horizon")).isNull();
        assertThat(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(thread, aliceId)).isEmpty();
    }

    private ResultActions perform(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.contextPath("/api").header("Authorization", "Bearer " + token));
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }
}
