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
    @DisplayName("F-234 / SF-234-11 — scènes par famille, part de la générique, nœuds, touchers, mode sobre, Montrer ; isolés")
    void scenesMeasure() throws Exception {
        event(adminToken, "{\"event\":\"SCENES\",\"scenes\":{\"S3\":3,\"GENERIQUE\":1},\"lit\":4,\"shows\":1}")
                .andExpect(status().isNoContent());
        event(adminToken, "{\"event\":\"SCENES\",\"scenes\":{\"S3\":1,\"S5\":3},\"lit\":2}")
                .andExpect(status().isNoContent());
        event(adminToken, "{\"event\":\"TOUCH\"}").andExpect(status().isNoContent());
        event(adminToken, "{\"event\":\"SOBER\"}").andExpect(status().isNoContent());
        // Aucun contenu ne passe : un identifiant de scène qui n'en est pas un est refusé.
        event(adminToken, "{\"event\":\"SCENES\",\"scenes\":{\"Zahi Ben\":1}}").andExpect(status().isBadRequest());
        event(adminToken, "{\"event\":\"SCENES\",\"scenes\":{\"S3\":100000}}").andExpect(status().isBadRequest());

        measure(adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.scenes.shown").value(8))
                .andExpect(jsonPath("$.scenes.genericSharePct").value(12.5))
                .andExpect(jsonPath("$.scenes.perScene.S3").value(4))
                .andExpect(jsonPath("$.scenes.perScene.S5").value(3))
                .andExpect(jsonPath("$.scenes.nodesLit").value(6))
                .andExpect(jsonPath("$.scenes.nodeTouches").value(1))
                .andExpect(jsonPath("$.scenes.soberOn").value(1))
                .andExpect(jsonPath("$.scenes.showRequests").value(1));
        measure(otherAdminToken).andExpect(status().isOk()).andExpect(jsonPath("$.scenes.shown").value(0));
    }

    @Test
    @DisplayName("F-236 / SF-236-06 — rendu : sessions 3D / 2D, part de la 3D, détail par clé ; vocabulaire fermé ; isolé")
    void renderMeasure() throws Exception {
        event(adminToken, "{\"event\":\"RENDER\",\"mode\":\"3d\",\"render\":{\"sec_high\":120,\"fps_50_60\":1,"
                + "\"gestures\":4,\"t_subject\":2,\"brains\":1,\"entries\":1}}").andExpect(status().isNoContent());
        event(adminToken, "{\"event\":\"RENDER\",\"mode\":\"3D\",\"render\":{\"sec_high\":30,\"entries_skipped\":1}}")
                .andExpect(status().isNoContent());
        event(adminToken, "{\"event\":\"render\",\"mode\":\"2d\",\"render\":{\"r_no_webgl\":1}}")
                .andExpect(status().isNoContent());
        // Aucun contenu ne passe : clé hors vocabulaire, nombre hors bornes, mode inconnu ⇒ 400.
        event(adminToken, "{\"event\":\"RENDER\",\"mode\":\"3d\",\"render\":{\"Zahi\":1}}")
                .andExpect(status().isBadRequest());
        event(adminToken, "{\"event\":\"RENDER\",\"mode\":\"3d\",\"render\":{\"sec_high\":100000}}")
                .andExpect(status().isBadRequest());
        event(adminToken, "{\"event\":\"RENDER\",\"mode\":\"vr\"}").andExpect(status().isBadRequest());

        measure(adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.render.sessions3d").value(2))
                .andExpect(jsonPath("$.render.sessions2d").value(1))
                .andExpect(jsonPath("$.render.share3dPct").value(66.7))
                .andExpect(jsonPath("$.render.counts.sec_high").value(150))
                .andExpect(jsonPath("$.render.counts.r_no_webgl").value(1))
                .andExpect(jsonPath("$.render.counts.t_subject").value(2))
                .andExpect(jsonPath("$.render.counts.entries_skipped").value(1));
        measure(otherAdminToken).andExpect(status().isOk()).andExpect(jsonPath("$.render.sessions3d").value(0))
                .andExpect(jsonPath("$.render.sessions2d").value(0));
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
