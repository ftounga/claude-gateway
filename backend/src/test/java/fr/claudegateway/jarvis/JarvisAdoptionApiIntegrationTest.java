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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** La mesure d'adoption de Jarvis (F-231 / SF-231-05, D9) : événements, mesure ADMIN, isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisAdoptionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String otherAdminToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtService.generateToken(user("jarvis-adopt-a-" + UUID.randomUUID() + "@ex.com", UserRole.ADMIN));
        otherAdminToken = jwtService.generateToken(user("jarvis-adopt-b-" + UUID.randomUUID() + "@ex.com", UserRole.ADMIN));
    }

    private User user(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(role).createdAt(OffsetDateTime.now()).build());
    }

    private ResultActions event(String token, String json) throws Exception {
        return mockMvc.perform(post("/api/jarvis/usage").contextPath("/api").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions measure(String token) throws Exception {
        return mockMvc.perform(get("/api/admin/jarvis/measure").contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("sessions, minutes parlées (bornées) et réveils comptés ; la mesure ne voit que le compte")
    void countsAndIsolates() throws Exception {
        event(adminToken, "{\"event\":\"SESSION\"}").andExpect(status().isNoContent());
        event(adminToken, "{\"event\":\"SESSION\"}").andExpect(status().isNoContent());
        event(adminToken, "{\"event\":\"spoken\",\"seconds\":90}").andExpect(status().isNoContent());
        event(adminToken, "{\"event\":\"SPOKEN\",\"seconds\":99999}").andExpect(status().isNoContent());
        event(adminToken, "{\"event\":\"WAKE\"}").andExpect(status().isNoContent());
        // SF-233-11 — les retours au repos de la conversation mains libres.
        event(adminToken, "{\"event\":\"IDLE_END\"}").andExpect(status().isNoContent());
        event(adminToken, "{\"event\":\"idle_end\"}").andExpect(status().isNoContent());

        measure(adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.activeDays").value(1))
                .andExpect(jsonPath("$.sessions").value(2))
                .andExpect(jsonPath("$.spokenMinutes").value(11.5))
                .andExpect(jsonPath("$.wakes").value(1))
                .andExpect(jsonPath("$.idleEnds").value(2))
                .andExpect(jsonPath("$.voiceCostEur").value(0.0));

        measure(otherAdminToken).andExpect(status().isOk()).andExpect(jsonPath("$.sessions").value(0))
                .andExpect(jsonPath("$.idleEnds").value(0));
    }

    @Test
    @DisplayName("400 événement inconnu ; 403 mesure pour un non-ADMIN ; 401 sans jeton")
    void errors() throws Exception {
        event(adminToken, "{\"event\":\"DANCE\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"));
        String userToken = jwtService.generateToken(user("jarvis-adopt-u-" + UUID.randomUUID() + "@ex.com",
                UserRole.USER));
        measure(userToken).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/jarvis/usage").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"SESSION\"}")).andExpect(status().isUnauthorized());
    }
}
