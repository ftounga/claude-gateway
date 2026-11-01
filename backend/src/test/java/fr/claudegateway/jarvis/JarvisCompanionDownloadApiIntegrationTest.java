package fr.claudegateway.jarvis;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Le compagnon Android servi par le chemin des paquets runner (F-232 / SF-232-02) — gateway SANS APK
 * configuré : version indisponible, 404 explicite, routes du runner inchangées.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCompanionDownloadApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void versionIndisponible_etTelechargement404() throws Exception {
        mockMvc.perform(get("/api/runner/download/android/version").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.versionCode").doesNotExist());
        mockMvc.perform(get("/api/runner/download/android").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("jarvis_companion_unavailable"));
    }

    @Test
    void lesRoutesDuRunnerSontInchangees() throws Exception {
        mockMvc.perform(get("/api/runner/download/formats").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jar").isBoolean());
    }
}
