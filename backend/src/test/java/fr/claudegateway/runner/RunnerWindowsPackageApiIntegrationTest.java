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
import org.junit.jupiter.api.DisplayName;
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
 * Paquet autonome Windows (F-44 / SF-44-02) : il se télécharge <b>à côté</b> du jar, sans
 * authentification, et son absence se dit par un code d'erreur qui lui est propre.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerWindowsPackageApiIntegrationTest {

    private static final byte[] JAR_BYTES = "faux-jar".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ZIP_BYTES = "faux-paquet-windows".getBytes(StandardCharsets.UTF_8);

    private static Path jarPath;
    private static Path packagePath;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        try {
            Path dir = Files.createTempDirectory("runner-windows-package-test");
            jarPath = dir.resolve("claude-runner.jar");
            packagePath = dir.resolve("claude-runner-windows-x64.zip");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        jarPath.toFile().deleteOnExit();
        packagePath.toFile().deleteOnExit();
        registry.add("app.runner.jar-path", () -> jarPath.toString());
        registry.add("app.runner.windows-package-path", () -> packagePath.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void writeBothFormats() throws IOException {
        Files.write(jarPath, JAR_BYTES);
        Files.write(packagePath, ZIP_BYTES);
    }

    @Test
    @DisplayName("le paquet se télécharge sous son propre nom, sans authentification")
    void thePackageIsServedPubliclyUnderItsOwnName() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/runner/download/windows").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"claude-runner-windows-x64.zip\""))
                .andExpect(header().longValue("Content-Length", ZIP_BYTES.length))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(ZIP_BYTES);
    }

    @Test
    @DisplayName("le jar reste servi à l'identique")
    void theJarIsUntouched() throws Exception {
        // Non-régression : F-44 ajoute un format, elle n'en remplace aucun.
        MvcResult result = mockMvc.perform(get("/api/runner/download").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"claude-runner.jar\""))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(JAR_BYTES);
    }

    @Test
    @DisplayName("un paquet absent se dit par un code qui lui est propre")
    void aMissingPackageHasItsOwnErrorCode() throws Exception {
        Files.delete(packagePath);

        // D2 : « le jar manque » et « le paquet n'a pas été empaqueté » ne sont pas le même
        // incident d'exploitation. Les confondre ferait chercher au mauvais endroit.
        mockMvc.perform(get("/api/runner/download/windows").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("runner_package_unavailable"));

        // Et le jar, lui, reste disponible : un format absent n'emporte pas l'autre.
        mockMvc.perform(get("/api/runner/download").contextPath("/api"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("les formats disponibles se lisent sans rien télécharger")
    void availableFormatsCanBeReadWithoutDownloading() throws Exception {
        // D3 : l'écran masque un format absent au lieu d'offrir un lien mort. Encore faut-il qu'il
        // puisse le savoir sans déclencher 39 Mo de téléchargement.
        mockMvc.perform(get("/api/runner/download/formats").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jar").value(true))
                .andExpect(jsonPath("$.windowsPackage").value(true));

        Files.delete(packagePath);

        mockMvc.perform(get("/api/runner/download/formats").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jar").value(true))
                .andExpect(jsonPath("$.windowsPackage").value(false));
    }
}
