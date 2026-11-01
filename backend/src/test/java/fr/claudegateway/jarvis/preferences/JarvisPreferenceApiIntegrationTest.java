package fr.claudegateway.jarvis.preferences;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-235 / SF-235-09 (D18) — {@code GET/PUT /api/jarvis/preferences} : défaut léger, par compte, 400, 401. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisPreferenceApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    private String token(String prefix) {
        User user = userRepository.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        return "Bearer " + jwtService.generateToken(user);
    }

    @Test
    void defaultLightThenNoneForThisAccountOnly() throws Exception {
        String alice = token("prefs-alice");
        String bob = token("prefs-bob");

        mockMvc.perform(get("/api/jarvis/preferences").contextPath("/api").header("Authorization", alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.humour").value("LIGHT"));
        mockMvc.perform(put("/api/jarvis/preferences").contextPath("/api").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"humour\":\"NONE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.humour").value("NONE"));
        mockMvc.perform(get("/api/jarvis/preferences").contextPath("/api").header("Authorization", alice))
                .andExpect(jsonPath("$.humour").value("NONE"));
        mockMvc.perform(get("/api/jarvis/preferences").contextPath("/api").header("Authorization", bob))
                .andExpect(jsonPath("$.humour").value("LIGHT"));
        mockMvc.perform(put("/api/jarvis/preferences").contextPath("/api").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"humour\":\"beaucoup\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/jarvis/preferences").contextPath("/api")).andExpect(status().isUnauthorized());
    }
}
