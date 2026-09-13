package fr.claudegateway.runner.update;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F-111 / SF-111-03 — les routes de mise à jour, <b>sans authentification</b>, à travers la vraie chaîne
 * de sécurité {@code /runner/**}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerUpdateApiIntegrationTest {

    private static final String ID = RunnerUpdateArtifactsTest.ID;

    @DynamicPropertySource
    static void artefacts(DynamicPropertyRegistry registry) {
        try {
            Path dir = Files.createTempDirectory("runner-update-api-test");
            RunnerUpdateArtifactsTest.deposit(dir, ID, true, null);
            registry.add("app.runner.update-dir", dir::toString);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void manifestIsPublic() throws Exception {
        mockMvc.perform(get("/api/runner/update/manifest").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.signed").value(true))
                .andExpect(jsonPath("$.notes[0]").value("Une note."));
    }

    @Test
    void signedJarShaAndSignatureArePublic() throws Exception {
        mockMvc.perform(get("/api/runner/update/" + ID).contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(("jar " + ID).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        mockMvc.perform(get("/api/runner/update/" + ID + "/sha256").contextPath("/api"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/runner/update/" + ID + "/signature").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(content().string("c2lnbmF0dXJl"));
    }

    @Test
    void anotherVersionIsNotFound() throws Exception {
        mockMvc.perform(get("/api/runner/update/1.0.0-202609130000-aaa1111").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(RunnerUpdateController.UNAVAILABLE));
        mockMvc.perform(get("/api/runner/update/1.0.0-202609130000-aaa1111/signature").contextPath("/api"))
                .andExpect(status().isNotFound());
    }

    @Test
    void neighbouringRoutesStayClosed() throws Exception {
        // Non-régression de la chaîne : seules les quatre routes déclarées sont ouvertes.
        mockMvc.perform(get("/api/runner/update/" + ID + "/autre").contextPath("/api")).andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/runner/update/manifest").contextPath("/api")).andExpect(status().isForbidden());
    }
}
