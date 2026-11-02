package fr.claudegateway.jarvis.timing;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Le chronomètre du tour vocal (F-233 / SF-233-01) : mesure rangée sans contenu, p50/p90 ADMIN, isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisLatencyApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private JarvisTurnTimingRepository timings;
    @Autowired private fr.claudegateway.atelier.AtelierMessageRepository messages;
    @Autowired private JwtService jwtService;

    private User admin;
    private User otherAdmin;
    private String adminToken;
    private String otherAdminToken;

    @BeforeEach
    void setUp() {
        admin = user("jarvis-lat-a-" + UUID.randomUUID() + "@ex.com", UserRole.ADMIN);
        otherAdmin = user("jarvis-lat-b-" + UUID.randomUUID() + "@ex.com", UserRole.ADMIN);
        adminToken = jwtService.generateToken(admin);
        otherAdminToken = jwtService.generateToken(otherAdmin);
    }

    private User user(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(role).createdAt(OffsetDateTime.now()).build());
    }

    private ResultActions report(String token, String json) throws Exception {
        return mockMvc.perform(post("/api/jarvis/timings").contextPath("/api").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions latency(String token) throws Exception {
        return mockMvc.perform(get("/api/admin/jarvis/latency").contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("deux tours rangés : p50/p90 par étape, interruptions cumulées, rien pour un autre ADMIN")
    void recordsAndMeasures() throws Exception {
        report(adminToken, "{\"listenMode\":\"BATCH\",\"sttProvider\":\"openai\",\"ttsProvider\":\"openai\","
                + "\"transcriptMs\":1200,\"sentMs\":1250,\"firstTokenMs\":4000,\"firstSoundMs\":9000,\"doneMs\":12000,"
                + "\"interruptionsDetected\":2,\"interruptionsConfirmed\":1,\"falseTriggers\":1}")
                .andExpect(status().isNoContent());
        report(adminToken, "{\"listenMode\":\"STREAM\",\"toolTurn\":true,\"transcriptMs\":200,\"sentMs\":220,"
                + "\"firstTokenMs\":3000,\"firstSoundMs\":5000,\"doneMs\":20000,\"acknowledgements\":3}")
                .andExpect(status().isNoContent());

        latency(adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.turns").value(2))
                .andExpect(jsonPath("$.toolTurns").value(1))
                .andExpect(jsonPath("$.steps[?(@.step=='transcript')].count").value(2))
                .andExpect(jsonPath("$.steps[?(@.step=='transcript')].p50").value(200))
                .andExpect(jsonPath("$.steps[?(@.step=='transcript')].p90").value(1200))
                .andExpect(jsonPath("$.steps[?(@.step=='reaction')].count").value(0))
                .andExpect(jsonPath("$.interruptions.detected").value(2))
                .andExpect(jsonPath("$.interruptions.confirmed").value(1))
                .andExpect(jsonPath("$.interruptions.acknowledgements").value(3))
                .andExpect(jsonPath("$.interruptions.falseTriggers").value(1))
                .andExpect(jsonPath("$.byListenMode.BATCH").value(1))
                .andExpect(jsonPath("$.byListenMode.STREAM").value(1));

        latency(otherAdminToken).andExpect(status().isOk()).andExpect(jsonPath("$.turns").value(0));
    }

    @Test
    @DisplayName("F-235 / SF-235-07 — mesure du cerveau rapide : par cerveau, délégation, corrections, creuses ; ADMIN, sous user_id")
    void fastBrainMeasure() throws Exception {
        report(adminToken, "{\"brain\":\"FAST\",\"fastModel\":\"claude-haiku-5-5\",\"fastFirstTokenMs\":400,"
                + "\"firstSoundMs\":1200,\"fillersRemoved\":1}").andExpect(status().isNoContent());
        report(adminToken, "{\"brain\":\"FAST\",\"fastFirstTokenMs\":600,\"firstSoundMs\":1500,\"correction\":true}")
                .andExpect(status().isNoContent());
        report(adminToken, "{\"brain\":\"FAST_DELEGATED\",\"firstSoundMs\":1300,\"delegationReason\":\"grounding\","
                + "\"groundingTrips\":1}").andExpect(status().isNoContent());
        report(adminToken, "{\"brain\":\"FAST_DELEGATED\",\"forcedDelegation\":true,\"delegationReason\":\"forced\"}")
                .andExpect(status().isNoContent());
        report(adminToken, "{\"firstSoundMs\":9700}").andExpect(status().isNoContent());
        report(adminToken, "{\"brain\":\"PIGEON\"}").andExpect(status().isBadRequest());
        report(adminToken, "{\"delegationReason\":\"caprice\"}").andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/admin/jarvis/fast-brain/measure").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns").value(5))
                .andExpect(jsonPath("$.fastAnswers").value(2))
                .andExpect(jsonPath("$.fastDelegated").value(2))
                .andExpect(jsonPath("$.opusTurns").value(1))
                .andExpect(jsonPath("$.firstSoundByBrain.FAST.p50").value(1200))
                .andExpect(jsonPath("$.firstSoundByBrain.OPUS.p50").value(9700))
                .andExpect(jsonPath("$.fastFirstToken.p90").value(600))
                .andExpect(jsonPath("$.delegationRate").value(0.5))
                .andExpect(jsonPath("$.automaticDelegations.grounding").value(1))
                .andExpect(jsonPath("$.forcedDelegations").value(1))
                .andExpect(jsonPath("$.corrections").value(1))
                .andExpect(jsonPath("$.correctionRate").value(0.5))
                .andExpect(jsonPath("$.hollowOrRepeatedShare").value(0.5))
                .andExpect(jsonPath("$.opusTurnsAvoided").value(2));
        mockMvc.perform(get("/api/admin/jarvis/fast-brain/measure").contextPath("/api")
                        .header("Authorization", "Bearer " + otherAdminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turns").value(0));
        String userToken = jwtService.generateToken(user("jarvis-fbm-u-" + UUID.randomUUID() + "@ex.com",
                UserRole.USER));
        mockMvc.perform(get("/api/admin/jarvis/fast-brain/measure").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("le sujet d'autrui est ignoré (ligne sans sujet) ; le sien est rattaché")
    void subjectIsolation() throws Exception {
        Workspace mine = workspaceRepository.save(Workspace.builder().userId(admin.getId()).name("lat-mine").build());
        Workspace theirs = workspaceRepository.save(Workspace.builder().userId(otherAdmin.getId())
                .name("lat-theirs").build());
        report(adminToken, "{\"workspaceId\":\"" + theirs.getId() + "\",\"doneMs\":10}").andExpect(status().isNoContent());
        report(adminToken, "{\"workspaceId\":\"" + mine.getId() + "\",\"doneMs\":20}").andExpect(status().isNoContent());

        var rows = timings.findByUserIdAndCreatedAtGreaterThanEqual(admin.getId(), OffsetDateTime.now().minusDays(1));
        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getDoneMs()).isEqualTo(10);
            assertThat(r.getWorkspaceId()).isNull();
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getDoneMs()).isEqualTo(20);
            assertThat(r.getWorkspaceId()).isEqualTo(mine.getId());
        });
    }

    @Test
    @DisplayName("F-239 / SF-239-02 — une parole refusée : raison (liste fermée) et longueur gardées, jamais le texte")
    void rejectedSpeechIsTracedWithoutText() throws Exception {
        report(adminToken, "{\"listenMode\":\"CONV_STREAM\",\"falseTriggers\":1,\"rejectedKind\":\"hallucination\","
                + "\"rejectedChars\":49}").andExpect(status().isNoContent());
        report(adminToken, "{\"listenMode\":\"CONV_STREAM\",\"rejectedChars\":12}").andExpect(status().isNoContent());
        report(adminToken, "{\"rejectedKind\":\"bavardage\"}").andExpect(status().isBadRequest());

        var rows = timings.findByUserIdAndCreatedAtGreaterThanEqual(admin.getId(), OffsetDateTime.now().minusDays(1));
        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getRejectedKind()).isEqualTo("hallucination");
            assertThat(r.getRejectedChars()).isEqualTo(49);
            assertThat(r.getFalseTriggers()).isEqualTo(1);
        });
        // Sans raison, la longueur n'est pas gardée.
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getRejectedKind()).isNull();
            assertThat(r.getRejectedChars()).isNull();
        });
        assertThat(timings.findByUserIdAndCreatedAtGreaterThanEqual(otherAdmin.getId(),
                OffsetDateTime.now().minusDays(1))).isEmpty();
    }

    @Test
    @DisplayName("F-240 / SF-240-01 — ce qui est dit : mots, durée, coupe, invitation, étage ; p50/p90 ADMIN ; isolation")
    void spokenLengthIsMeasured() throws Exception {
        report(adminToken, "{\"brain\":\"OPUS\",\"spokenWords\":24,\"spokenChars\":140,\"spokenMs\":7800,"
                + "\"announceWords\":6,\"oralCut\":false,\"invitation\":true,\"depthReached\":3,\"depthVia\":\"voix\"}")
                .andExpect(status().isNoContent());
        report(adminToken, "{\"brain\":\"FAST\",\"spokenWords\":40,\"spokenChars\":230,\"spokenMs\":12000,"
                + "\"oralCut\":true,\"depthReached\":2,\"depthVia\":\"TOUCHER\"}").andExpect(status().isNoContent());
        report(adminToken, "{\"doneMs\":10}").andExpect(status().isNoContent());
        // Un autre compte : ses mots ne comptent jamais pour l'administrateur.
        report(otherAdminToken, "{\"spokenWords\":300,\"spokenMs\":90000}").andExpect(status().isNoContent());
        // Bornes et listes fermées.
        report(adminToken, "{\"spokenWords\":-1}").andExpect(status().isBadRequest());
        report(adminToken, "{\"depthReached\":4,\"depthVia\":\"voix\"}").andExpect(status().isBadRequest());
        report(adminToken, "{\"depthReached\":2,\"depthVia\":\"clavier\"}").andExpect(status().isBadRequest());
        report(adminToken, "{\"spokenMs\":700000}").andExpect(status().isBadRequest());

        latency(adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.turns").value(3))
                .andExpect(jsonPath("$.spoken.answers").value(2))
                .andExpect(jsonPath("$.spoken.wordsP50").value(24))
                .andExpect(jsonPath("$.spoken.wordsP90").value(40))
                .andExpect(jsonPath("$.spoken.msP90").value(12000))
                .andExpect(jsonPath("$.spoken.announceWordsP90").value(6))
                .andExpect(jsonPath("$.spoken.cutRate").value(0.5))
                .andExpect(jsonPath("$.spoken.invitationRate").value(0.5))
                .andExpect(jsonPath("$.spoken.depthReached.2").value(1))
                .andExpect(jsonPath("$.spoken.depthReached.3").value(1))
                .andExpect(jsonPath("$.spoken.depthVia.voix").value(1))
                .andExpect(jsonPath("$.spoken.depthVia.toucher").value(1));
        latency(otherAdminToken).andExpect(jsonPath("$.spoken.answers").value(1))
                .andExpect(jsonPath("$.spoken.wordsP90").value(300));
    }

    @Test
    @DisplayName("F-240 / SF-240-01 — base de départ : mots des lignes 🎙 écrites par Opus, sous user_id, rapides exclues")
    void oralLinesBaseline() throws Exception {
        Workspace mine = workspaceRepository.save(Workspace.builder().userId(admin.getId()).name("lat-oral").build());
        Workspace theirs = workspaceRepository.save(Workspace.builder().userId(otherAdmin.getId())
                .name("lat-oral-b").build());
        messages.save(fr.claudegateway.atelier.AtelierMessage.builder().workspaceId(mine.getId())
                .userId(admin.getId()).role("ASSISTANT").via("JARVIS")
                .content("🎙 Oui, Monsieur : le chantier 3 est terminé.\n<<essentiel>>\nFini.\n<</essentiel>>").build());
        messages.save(fr.claudegateway.atelier.AtelierMessage.builder().workspaceId(mine.getId())
                .userId(admin.getId()).role("ASSISTANT").via("JARVIS").brain("FAST")
                .content("🎙 réponse rapide hors mesure avec beaucoup de mots ici").build());
        messages.save(fr.claudegateway.atelier.AtelierMessage.builder().workspaceId(theirs.getId())
                .userId(otherAdmin.getId()).role("ASSISTANT").via("JARVIS")
                .content("🎙 Une ligne d'un autre compte.").build());

        latency(adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.oralLines.replies").value(1))
                .andExpect(jsonPath("$.oralLines.wordsP90").value(7));
        latency(otherAdminToken).andExpect(jsonPath("$.oralLines.replies").value(1))
                .andExpect(jsonPath("$.oralLines.wordsP90").value(5));
    }

    @Test
    @DisplayName("400 marque hors bornes, compteur négatif, fournisseur illisible, mode inconnu ; 403 non-ADMIN ; 401")
    void errors() throws Exception {
        report(adminToken, "{\"doneMs\":-1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"));
        report(adminToken, "{\"doneMs\":700000}").andExpect(status().isBadRequest());
        report(adminToken, "{\"falseTriggers\":-2}").andExpect(status().isBadRequest());
        report(adminToken, "{\"sttProvider\":\"Open AI!\"}").andExpect(status().isBadRequest());
        report(adminToken, "{\"listenMode\":\"PIGEON\"}").andExpect(status().isBadRequest());
        // SF-233-11 — un tour pris en conversation mains libres.
        report(adminToken, "{\"listenMode\":\"CONV_STREAM\"}").andExpect(status().isNoContent());
        latency(adminToken).andExpect(jsonPath("$.byListenMode.CONV_STREAM").value(1));
        String userToken = jwtService.generateToken(user("jarvis-lat-u-" + UUID.randomUUID() + "@ex.com",
                UserRole.USER));
        latency(userToken).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/jarvis/timings").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnauthorized());
    }
}
