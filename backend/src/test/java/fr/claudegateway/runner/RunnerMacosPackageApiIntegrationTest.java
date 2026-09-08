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
 * Paquets autonomes macOS (F-44 / SF-44-03) : deux architectures, servies <b>à côté</b> du jar et du
 * paquet Windows, sans authentification, et dont l'absence se dit sans emporter les autres formats.
 *
 * <p>Aucun format n'en remplace un autre : c'est la propriété que ces tests protègent, autant que
 * l'ajout lui-même.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerMacosPackageApiIntegrationTest {

    private static final byte[] JAR_BYTES = "faux-jar".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WINDOWS_BYTES = "faux-paquet-windows".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AARCH64_BYTES = "faux-paquet-macos-arm".getBytes(StandardCharsets.UTF_8);
    private static final byte[] X64_BYTES = "faux-paquet-macos-intel".getBytes(StandardCharsets.UTF_8);

    private static Path jarPath;
    private static Path windowsPath;
    private static Path aarch64Path;
    private static Path x64Path;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        try {
            Path dir = Files.createTempDirectory("runner-macos-package-test");
            jarPath = dir.resolve("claude-runner.jar");
            windowsPath = dir.resolve("claude-runner-windows-x64.zip");
            aarch64Path = dir.resolve("claude-runner-macos-aarch64.tar.gz");
            x64Path = dir.resolve("claude-runner-macos-x64.tar.gz");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        jarPath.toFile().deleteOnExit();
        windowsPath.toFile().deleteOnExit();
        aarch64Path.toFile().deleteOnExit();
        x64Path.toFile().deleteOnExit();
        registry.add("app.runner.jar-path", () -> jarPath.toString());
        registry.add("app.runner.windows-package-path", () -> windowsPath.toString());
        registry.add("app.runner.macos-aarch64-package-path", () -> aarch64Path.toString());
        registry.add("app.runner.macos-x64-package-path", () -> x64Path.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void writeEveryFormat() throws IOException {
        Files.write(jarPath, JAR_BYTES);
        Files.write(windowsPath, WINDOWS_BYTES);
        Files.write(aarch64Path, AARCH64_BYTES);
        Files.write(x64Path, X64_BYTES);
    }

    @Test
    @DisplayName("le paquet Apple Silicon se télécharge sous son propre nom, sans authentification")
    void theAppleSiliconPackageIsServedPubliclyUnderItsOwnName() throws Exception {
        MvcResult result = mockMvc
                .perform(get("/api/runner/download/macos-aarch64").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"claude-runner-macos-aarch64.tar.gz\""))
                .andExpect(header().longValue("Content-Length", AARCH64_BYTES.length))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(AARCH64_BYTES);
    }

    @Test
    @DisplayName("le paquet Intel se télécharge sous son propre nom, sans authentification")
    void theIntelPackageIsServedPubliclyUnderItsOwnName() throws Exception {
        // Deux routes, pas un `?arch=` (D1) : chaque archive est une ressource distincte, et le
        // contenu servi doit prouver qu'elles ne se confondent pas.
        MvcResult result = mockMvc.perform(get("/api/runner/download/macos-x64").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"claude-runner-macos-x64.tar.gz\""))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(X64_BYTES);
    }

    @Test
    @DisplayName("un paquet macOS absent se dit sans emporter les autres formats")
    void aMissingMacosPackageDoesNotTakeTheOthersDown() throws Exception {
        Files.delete(aarch64Path);

        // D2 : le code reste celui des paquets — c'est bien le même incident d'exploitation — et
        // c'est le MESSAGE qui nomme la plateforme et l'architecture.
        mockMvc.perform(get("/api/runner/download/macos-aarch64").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("runner_package_unavailable"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Apple Silicon")));

        // Les trois autres formats restent servis : un format absent n'emporte jamais les autres.
        mockMvc.perform(get("/api/runner/download").contextPath("/api"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/runner/download/windows").contextPath("/api"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/runner/download/macos-x64").contextPath("/api"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("le message du paquet Intel absent nomme son architecture")
    void theIntelMessageNamesItsArchitecture() throws Exception {
        Files.delete(x64Path);

        mockMvc.perform(get("/api/runner/download/macos-x64").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("runner_package_unavailable"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Intel")));
    }

    @Test
    @DisplayName("les quatre formats se lisent d'un coup, chacun suivant la présence du fichier")
    void everyFormatIsReportedIndependently() throws Exception {
        mockMvc.perform(get("/api/runner/download/formats").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jar").value(true))
                .andExpect(jsonPath("$.windowsPackage").value(true))
                .andExpect(jsonPath("$.macosAarch64Package").value(true))
                .andExpect(jsonPath("$.macosX64Package").value(true));

        Files.delete(aarch64Path);
        Files.delete(x64Path);

        // D3 : l'écran masque un format absent au lieu d'offrir un lien mort — encore faut-il qu'il
        // puisse le savoir sans déclencher 39 Mo de téléchargement.
        mockMvc.perform(get("/api/runner/download/formats").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jar").value(true))
                .andExpect(jsonPath("$.windowsPackage").value(true))
                .andExpect(jsonPath("$.macosAarch64Package").value(false))
                .andExpect(jsonPath("$.macosX64Package").value(false));
    }
}
