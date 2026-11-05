package fr.claudegateway.jarvis.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** F-248 / SF-248-01 — la mesure des pancartes : colonnes rangées, bornes, agrégats, ADMIN, isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCardsMeasureApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisCardsProperties properties;

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
        return userRepository.save(User.builder().email("jarvis-cards-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(role).createdAt(OffsetDateTime.now()).build());
    }

    private ResultActions post(String token, String path, String json) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contextPath("/api").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions measure(String token) throws Exception {
        return mockMvc.perform(get("/api/admin/jarvis/cards/measure").contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("coupe-circuit : activé par défaut ; « false » l'éteint")
    void killSwitch() {
        assertThat(properties.on()).isTrue();
        assertThat(JarvisCardsProperties.defaults().on()).isTrue();
        assertThat(new JarvisCardsProperties(false).on()).isFalse();
    }

    @Test
    @DisplayName("délai premier mot → pancartes, K1, compteurs c_* et fps_* ; 400 hors bornes ; rien d'un autre ADMIN ; 403 USER")
    void measures() throws Exception {
        post(adminToken, "/api/jarvis/timings", "{\"brain\":\"FAST\",\"firstSoundMs\":1000,\"fastFirstTokenMs\":300,"
                + "\"cardsLagMs\":100,\"cardsCount\":3}").andExpect(status().isNoContent());
        post(adminToken, "/api/jarvis/timings", "{\"brain\":\"FAST\",\"firstSoundMs\":2000,\"fastFirstTokenMs\":500,"
                + "\"cardsLagMs\":300,\"cardsCount\":1}").andExpect(status().isNoContent());
        post(adminToken, "/api/jarvis/timings", "{\"brain\":\"FAST\",\"firstSoundMs\":1500}")
                .andExpect(status().isNoContent());
        post(adminToken, "/api/jarvis/timings", "{\"cardsLagMs\":-1}").andExpect(status().isBadRequest());
        post(adminToken, "/api/jarvis/timings", "{\"cardsCount\":5000}").andExpect(status().isBadRequest());
        post(adminToken, "/api/jarvis/usage", "{\"event\":\"RENDER\",\"mode\":\"3d\",\"render\":{\"c_set\":4,"
                + "\"c_open\":1,\"c_close2s\":1,\"fps_60p\":2}}").andExpect(status().isNoContent());

        measure(adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.turns").value(3))
                .andExpect(jsonPath("$.voice.firstSoundP50").value(1500))
                .andExpect(jsonPath("$.voice.firstSoundP95").value(2000))
                .andExpect(jsonPath("$.voice.fastFirstTokenP50").value(300))
                .andExpect(jsonPath("$.lag.sets").value(2))
                .andExpect(jsonPath("$.lag.p90").value(300))
                .andExpect(jsonPath("$.lag.beforeFirstWord").value(0))
                .andExpect(jsonPath("$.counts.c_set").value(4))
                .andExpect(jsonPath("$.fps.fps_60p").value(2))
                .andExpect(jsonPath("$.openRate").value(0.25))
                .andExpect(jsonPath("$.close2sRate").value(0.25));

        measure(otherAdminToken).andExpect(status().isOk()).andExpect(jsonPath("$.turns").value(0))
                .andExpect(jsonPath("$.lag.sets").value(0)).andExpect(jsonPath("$.openRate").doesNotExist());
        measure(userToken).andExpect(status().isForbidden());
    }
}
