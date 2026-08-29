package fr.claudegateway.runner;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Téléchargement du binaire runner (F-38 / SF-38-03) — cas <b>non configuré</b> : sans
 * {@code app.runner.jar-path}, {@code GET /runner/download} répond un 404 explicite. Couvre aussi la
 * non-régression de la chaîne principale et de {@code POST /runner/pair} après l'ajout de l'entrée
 * {@code /runner/download} dans la chaîne dédiée {@code /runner/**}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerDownloadApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void downloadReturns404WhenNoJarConfigured() throws Exception {
        mockMvc.perform(get("/api/runner/download").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("runner_jar_unavailable"));
    }

    // ---------- Non-régression : chaîne principale et /runner/pair inchangés ----------

    @Test
    void mainSecurityChainStillRequiresJwt() throws Exception {
        mockMvc.perform(get("/api/me").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/workspaces").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pairEndpointStillRejectsInvalidCode() throws Exception {
        mockMvc.perform(post("/api/runner/pair").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ZZZZZZZZ\",\"label\":\"poste-dev\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("pairing_invalid"));
    }

    @Test
    void unknownRunnerPathStillDenied() throws Exception {
        // La chaîne dédiée reste en denyAll hors des entrées explicitement ouvertes.
        mockMvc.perform(get("/api/runner/whatever").contextPath("/api"))
                .andExpect(status().isForbidden());
    }
}
