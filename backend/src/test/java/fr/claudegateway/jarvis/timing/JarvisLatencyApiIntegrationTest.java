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
