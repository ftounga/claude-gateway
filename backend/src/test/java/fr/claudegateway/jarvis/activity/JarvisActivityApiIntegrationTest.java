package fr.claudegateway.jarvis.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import fr.claudegateway.jarvis.RenderCounts;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-236 / SF-236-26 — <b>la carte d'activité 7 jours</b> : tours par sujet et par jour, <b>rien de Bob</b> (A/B),
 * bornes (7 jours, sujets du compte), <b>aucun contenu</b> dans la réponse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisActivityApiIntegrationTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final String SECRET = "CONTENU-SECRET-DU-TOUR";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private AtelierMessageRepository messageRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private Workspace forge;
    private Workspace doc;
    private Workspace bobSubject;

    @BeforeEach
    void setUp() {
        User alice = seedUser("jarvis-activity-alice-" + UUID.randomUUID() + "@ex.com");
        User bob = seedUser("jarvis-activity-bob-" + UUID.randomUUID() + "@ex.com");
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        forge = seedWorkspace(alice.getId(), "Migration Forge", false);
        doc = seedWorkspace(alice.getId(), "Doc archi", false);
        seedWorkspace(alice.getId(), "teams", true);
        bobSubject = seedWorkspace(bob.getId(), "Bob", false);
        LocalDate today = LocalDate.now(PARIS);
        turn(forge, alice.getId(), today, 3);       // 3 tours aujourd'hui
        turn(forge, alice.getId(), today.minusDays(2), 1);
        turn(doc, alice.getId(), today.minusDays(6), 2);
        turn(doc, alice.getId(), today.minusDays(9), 5); // hors période
        turn(bobSubject, bob.getId(), today, 4);
        // Une réponse n'est pas un tour.
        messageRepository.save(AtelierMessage.builder().workspaceId(forge.getId()).userId(alice.getId())
                .role("ASSISTANT").content(SECRET).build());
    }

    @Test
    @DisplayName("tours par sujet et par jour sur 7 jours, du plus ancien à aujourd'hui ; réponses et Teams exclus")
    void aliceActivity() throws Exception {
        String today = LocalDate.now(PARIS).toString();
        String body = perform(aliceToken, get("/api/jarvis/activity?days=7&tz=Europe/Paris"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[6]").value(today))
                .andExpect(jsonPath("$.subjects.length()").value(2))
                .andExpect(jsonPath("$.subjects[?(@.subjectId=='" + forge.getId() + "')].turns[6]").value(3))
                .andExpect(jsonPath("$.subjects[?(@.subjectId=='" + forge.getId() + "')].turns[4]").value(1))
                .andExpect(jsonPath("$.subjects[?(@.subjectId=='" + doc.getId() + "')].turns[0]").value(2))
                .andReturn().getResponse().getContentAsString();
        // Aucun contenu : ni texte de message, ni nom de sujet, ni identifiant de compte.
        assertThat(body).doesNotContain(SECRET).doesNotContain("Migration Forge").doesNotContain("tour de test")
                .doesNotContain(bobSubject.getId().toString());
    }

    @Test
    @DisplayName("isolation A/B : Bob ne voit que ses tours ; jamais ceux d'Alice")
    void isolation() throws Exception {
        String body = perform(bobToken, get("/api/jarvis/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjects.length()").value(1))
                .andExpect(jsonPath("$.subjects[0].subjectId").value(bobSubject.getId().toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(forge.getId().toString()).doesNotContain(doc.getId().toString());
    }

    @Test
    @DisplayName("bornes : days ∈ [1, 7] ; fuseau illisible ⇒ Europe/Paris ; non authentifié ⇒ 401")
    void bounds() throws Exception {
        perform(aliceToken, get("/api/jarvis/activity?days=30")).andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(7));
        perform(aliceToken, get("/api/jarvis/activity?days=0")).andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(1));
        perform(aliceToken, get("/api/jarvis/activity?tz=Mars/Olympus")).andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(7));
        mockMvc.perform(get("/api/jarvis/activity").contextPath("/api")).andExpect(status().isUnauthorized());
        assertThat(JarvisActivityService.MAX_SUBJECTS).isEqualTo(12 * 40);
        assertThat(JarvisActivityService.zoneOf("Mars/Olympus")).isEqualTo(PARIS);
    }

    @Test
    @DisplayName("mesure : les vues de l'itération 4 sont des clés admises (vocabulaire fermé)")
    void renderKeys() {
        assertThat(RenderCounts.KEYS).contains("replays", "presentations", "cockpits", "nights", "activity");
    }

    private void turn(Workspace w, UUID userId, LocalDate day, int n) {
        for (int i = 0; i < n; i++) {
            AtelierMessage m = messageRepository.save(AtelierMessage.builder().workspaceId(w.getId()).userId(userId)
                    .role("USER").content("tour de test").build());
            OffsetDateTime at = day.atTime(10, i).atZone(PARIS).toOffsetDateTime();
            jdbc.update("update atelier_messages set created_at = ? where id = ?", at, m.getId());
        }
    }

    private Workspace seedWorkspace(UUID userId, String name, boolean teams) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name(name).projectPath(name)
                .teamsTerminal(teams).executionTarget(WorkspaceExecutionTarget.SANDBOX).build());
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
