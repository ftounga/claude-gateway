package fr.claudegateway.jarvis.thread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * F-245 / SF-245-04 — <b>le fil du jour</b> ({@code GET /api/jarvis/thread/today}) : les échanges et les lignes-liens
 * dans l'ordre, la réponse réduite à sa ligne 🎙, rien de créé sans fil, isolation A/B.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisThreadTodayIntegrationTest {

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

    @BeforeEach
    void setUp() {
        User alice = seedUser("jarvis-today-alice-" + UUID.randomUUID() + "@ex.com");
        User bob = seedUser("jarvis-today-bob-" + UUID.randomUUID() + "@ex.com");
        aliceId = alice.getId();
        bobId = bob.getId();
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
    }

    @Test
    @DisplayName("critère 1 : les échanges du jour et les liens, dans l'ordre ; la réponse réduite à sa ligne 🎙")
    void todayInOrder() throws Exception {
        UUID thread = threads.open(aliceId).getId();
        Workspace helios = workspaceRepository.save(Workspace.builder().userId(aliceId).name("Helios")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());
        save(thread, "USER", "Bonjour", AtelierMessage.BRAIN_FAST);
        save(thread, "ASSISTANT", "Bonjour Monsieur.", AtelierMessage.BRAIN_FAST);
        save(thread, "USER", "Fais le tour des clients", null);
        save(thread, "ASSISTANT", "🎙 Trois clients vont bien, Monsieur.\n\n## Détail\nlong détail", null);
        UUID link = links.open(aliceId, helios.getId(), 3, "relancer le déploiement");

        perform(aliceToken, get("/api/jarvis/thread/today")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].text").value("Bonjour"))
                .andExpect(jsonPath("$[0].fast").value(true))
                .andExpect(jsonPath("$[1].text").value("Bonjour Monsieur."))
                .andExpect(jsonPath("$[3].role").value("ASSISTANT"))
                .andExpect(jsonPath("$[3].text").value("Trois clients vont bien, Monsieur."))
                .andExpect(jsonPath("$[3].fast").value(false))
                .andExpect(jsonPath("$[4].id").value(link.toString()))
                .andExpect(jsonPath("$[4].role").value("LINK"))
                .andExpect(jsonPath("$[4].link.workspaceId").value(helios.getId().toString()))
                .andExpect(jsonPath("$[4].link.chantier").value(3))
                .andExpect(jsonPath("$[4].link.status").value("EN_COURS"));
    }

    @Test
    @DisplayName("sans fil : liste vide, et le fil n'est pas créé")
    void noThreadNoCreation() throws Exception {
        perform(aliceToken, get("/api/jarvis/thread/today")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(threads.find(aliceId)).isEmpty();
    }

    @Test
    @DisplayName("critère 5 : isolation — Bob ne voit rien du fil d'Alice")
    void isolation() throws Exception {
        UUID thread = threads.open(aliceId).getId();
        save(thread, "USER", "secret d'Alice", AtelierMessage.BRAIN_FAST);
        threads.open(bobId);

        perform(bobToken, get("/api/jarvis/thread/today")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("secret d'Alice"));
    }

    private void save(UUID thread, String role, String content, String brain) {
        messageRepository.save(AtelierMessage.builder().workspaceId(thread).userId(aliceId).role(role)
                .content(content).brain(brain).build());
    }

    private ResultActions perform(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.contextPath("/api").header("Authorization", "Bearer " + token));
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }
}
