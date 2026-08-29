package fr.claudegateway.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Téléchargement du binaire runner (F-38 / SF-38-03) — cas <b>configuré</b> : quand
 * {@code app.runner.jar-path} pointe un fichier présent, {@code GET /runner/download} sert ses octets
 * en pièce jointe ; quand le fichier disparaît, le même endpoint retombe sur le 404 explicite plutôt
 * que sur une erreur serveur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerDownloadConfiguredApiIntegrationTest {

    private static final byte[] JAR_BYTES = "faux-jar-de-test".getBytes(StandardCharsets.UTF_8);

    private static Path jarPath;

    @DynamicPropertySource
    static void jarPath(DynamicPropertyRegistry registry) {
        try {
            jarPath = Files.createTempDirectory("runner-download-test").resolve("claude-runner.jar");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        jarPath.toFile().deleteOnExit();
        registry.add("app.runner.jar-path", () -> jarPath.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void writeJar() throws IOException {
        Files.write(jarPath, JAR_BYTES);
    }

    @Test
    void downloadServesJarBytes() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/runner/download").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"claude-runner.jar\""))
                .andExpect(header().longValue("Content-Length", JAR_BYTES.length))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(JAR_BYTES);
    }

    @Test
    void downloadReturns404WhenConfiguredFileIsMissing() throws Exception {
        Files.delete(jarPath);

        mockMvc.perform(get("/api/runner/download").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("runner_jar_unavailable"));
    }
}
