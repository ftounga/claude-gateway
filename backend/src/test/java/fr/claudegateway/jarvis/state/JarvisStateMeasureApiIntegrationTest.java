package fr.claudegateway.jarvis.state;

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

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-247 / SF-247-01 — la mesure d'état : colonnes rangées, indicateur principal, ADMIN, isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisStateMeasureApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String otherAdminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtService.generateToken(user(UserRole.ADMIN));
        otherAdminToken = jwtService.generateToken(user(UserRole.ADMIN));
        userToken = jwtService.generateToken(user(UserRole.USER));
    }

    private User user(UserRole role) {
        return userRepository.save(User.builder().email("jarvis-state-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(role).createdAt(OffsetDateTime.now()).build());
    }

    private ResultActions report(String token, String json) throws Exception {
        return mockMvc.perform(post("/api/jarvis/timings").contextPath("/api").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions measure(String token) throws Exception {
        return mockMvc.perform(get("/api/admin/jarvis/state/measure").contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("questions d'état poste éteint : part des UNKNOWN, premier son p50/p90, rien pour un autre ADMIN, 403 USER")
    void measuresUnknownRate() throws Exception {
        report(adminToken, "{\"brain\":\"FAST\",\"firstSoundMs\":1000,\"fastFirstTokenMs\":300,"
                + "\"hostOffline\":true,\"stateQuestion\":true,\"stateAnswer\":\"UNKNOWN\"}")
                .andExpect(status().isNoContent());
        report(adminToken, "{\"brain\":\"FAST\",\"firstSoundMs\":2000,\"fastFirstTokenMs\":500,"
                + "\"hostOffline\":true,\"stateQuestion\":true,\"stateAnswer\":\"KNOWN_STALE\",\"stateBlockMs\":3}")
                .andExpect(status().isNoContent());
        report(adminToken, "{\"brain\":\"FAST\",\"firstSoundMs\":1500,\"hostOffline\":false,"
                + "\"stateQuestion\":false,\"stateAnswer\":\"NA\"}").andExpect(status().isNoContent());
        report(adminToken, "{\"stateAnswer\":\"PEUT-ETRE\"}").andExpect(status().isBadRequest());

        measure(adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.turns").value(3))
                .andExpect(jsonPath("$.stateQuestions").value(2))
                .andExpect(jsonPath("$.offlineStateQuestions").value(2))
                .andExpect(jsonPath("$.offlineAnswers.UNKNOWN").value(1))
                .andExpect(jsonPath("$.offlineAnswers.KNOWN_STALE").value(1))
                .andExpect(jsonPath("$.unknownRate").value(0.5))
                .andExpect(jsonPath("$.voice.turns").value(3))
                .andExpect(jsonPath("$.voice.firstSoundP50").value(1500))
                .andExpect(jsonPath("$.voiceOnStateQuestions.firstSoundP90").value(2000))
                .andExpect(jsonPath("$.stateBlockMsP95").value(3))
                .andExpect(jsonPath("$.opusTurns.count").value(0))
                .andExpect(jsonPath("$.runnerCalls.count").value(0));

        measure(otherAdminToken).andExpect(status().isOk()).andExpect(jsonPath("$.turns").value(0))
                .andExpect(jsonPath("$.unknownRate").doesNotExist());
        measure(userToken).andExpect(status().isForbidden());
    }
}
