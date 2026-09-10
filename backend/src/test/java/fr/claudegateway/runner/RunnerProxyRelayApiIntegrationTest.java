package fr.claudegateway.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.hamcrest.Matchers;
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
 * Le relais {@code px} servi par la gateway (F-59 / SF-59-01).
 *
 * <p>Deux propriétés sont protégées ici, et la seconde est la plus importante :</p>
 * <ul>
 *   <li>chaque plateforme est une ressource distincte, publique, dont l'absence se dit sans emporter
 *       les autres — ni aucun format du runner (F-38 / F-44) ;</li>
 *   <li><b>sans notice de licence, rien n'est servi</b> : c'est la condition MIT tenue par
 *       construction, et ce test est ce qui l'empêche de disparaître dans un refactoring.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerProxyRelayApiIntegrationTest {

    private static final byte[] WINDOWS_BYTES = "faux-px-windows".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MACOS_BYTES = "faux-px-macos-arm".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LINUX_BYTES = "faux-px-linux".getBytes(StandardCharsets.UTF_8);
    private static final String NOTICE = "MIT License\n\nCopyright (c) 2017-2026 Ganesh Viswanathan\n";

    private static Path windowsPath;
    private static Path macosPath;
    private static Path linuxPath;
    private static Path licensePath;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        try {
            Path dir = Files.createTempDirectory("runner-relay-test");
            windowsPath = dir.resolve("px-v0.11.0-windows-amd64.zip");
            macosPath = dir.resolve("px-v0.11.0-mac-arm64.tar.gz");
            linuxPath = dir.resolve("px-v0.11.0-linux-glibc-x86_64.tar.gz");
            licensePath = dir.resolve("px-LICENSE.txt");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        windowsPath.toFile().deleteOnExit();
        macosPath.toFile().deleteOnExit();
        linuxPath.toFile().deleteOnExit();
        licensePath.toFile().deleteOnExit();
        registry.add("app.runner.proxy-relay.windows-path", () -> windowsPath.toString());
        registry.add("app.runner.proxy-relay.macos-aarch64-path", () -> macosPath.toString());
        registry.add("app.runner.proxy-relay.linux-x64-path", () -> linuxPath.toString());
        registry.add("app.runner.proxy-relay.license-path", () -> licensePath.toString());
        registry.add("app.runner.proxy-relay.version", () -> "v0.11.0");
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void writeEveryArchiveAndTheNotice() throws IOException {
        Files.write(windowsPath, WINDOWS_BYTES);
        Files.write(macosPath, MACOS_BYTES);
        Files.write(linuxPath, LINUX_BYTES);
        Files.writeString(licensePath, NOTICE);
    }

    @Test
    @DisplayName("le relais Windows se télécharge sans authentification, sous son nom de version")
    void theWindowsRelayIsServedPubliclyUnderItsUpstreamName() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/runner/relay/windows").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"px-v0.11.0-windows-amd64.zip\""))
                .andExpect(header().longValue("Content-Length", WINDOWS_BYTES.length))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(WINDOWS_BYTES);
    }

    @Test
    @DisplayName("chaque plateforme sert son propre contenu — trois routes, jamais un ?platform=")
    void everyPlatformServesItsOwnArchive() throws Exception {
        MvcResult macos = mockMvc.perform(get("/api/runner/relay/macos-aarch64").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"px-v0.11.0-mac-arm64.tar.gz\""))
                .andReturn();
        MvcResult linux = mockMvc.perform(get("/api/runner/relay/linux-x64").contextPath("/api"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(macos.getResponse().getContentAsByteArray()).isEqualTo(MACOS_BYTES);
        assertThat(linux.getResponse().getContentAsByteArray()).isEqualTo(LINUX_BYTES);
    }

    @Test
    @DisplayName("la notice MIT s'affiche dans le navigateur — elle n'est pas un téléchargement")
    void theLicenseNoticeIsReadableInPlace() throws Exception {
        // `inline` et text/plain : une licence qu'il faut d'abord télécharger pour la lire n'est pas
        // montrée, et l'écran doit pouvoir l'ouvrir AVANT les 21 Mo de l'archive.
        mockMvc.perform(get("/api/runner/relay/license").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(header().string("Content-Disposition",
                        "inline; filename=\"px-LICENSE.txt\""))
                .andExpect(content().string(Matchers.containsString("MIT License")));
    }

    @Test
    @DisplayName("une archive absente se dit sans emporter les autres, ni les formats du runner")
    void aMissingArchiveDoesNotTakeTheOthersDown() throws Exception {
        Files.delete(windowsPath);

        mockMvc.perform(get("/api/runner/relay/windows").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("runner_relay_unavailable"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Windows")));

        mockMvc.perform(get("/api/runner/relay/macos-aarch64").contextPath("/api"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/runner/relay/linux-x64").contextPath("/api"))
                .andExpect(status().isOk());
        // Le relais est servi À CÔTÉ du runner : il ne lui prend rien (non-régression F-38 / F-44).
        mockMvc.perform(get("/api/runner/download/formats").contextPath("/api"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("sans notice de licence, AUCUNE archive n'est servie")
    void withoutTheNoticeNothingIsRedistributed() throws Exception {
        Files.delete(licensePath);

        // D3 — la condition MIT tenue par construction : redistribuer sans la notice serait une
        // violation ; ne rien servir n'en est pas une. Et le code d'erreur le dit : ce n'est pas un
        // défaut d'empaquetage, c'est un refus.
        mockMvc.perform(get("/api/runner/relay/windows").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("runner_relay_license_unavailable"));
        mockMvc.perform(get("/api/runner/relay/macos-aarch64").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("runner_relay_license_unavailable"));
        mockMvc.perform(get("/api/runner/relay/linux-x64").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("runner_relay_license_unavailable"));
        mockMvc.perform(get("/api/runner/relay/license").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("runner_relay_license_unavailable"));

        // L'écran ne doit pas proposer ce qu'on refuse de servir.
        mockMvc.perform(get("/api/runner/relay/formats").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows").value(false))
                .andExpect(jsonPath("$.macosAarch64").value(false))
                .andExpect(jsonPath("$.linuxX64").value(false))
                .andExpect(jsonPath("$.license").value(false));
    }

    @Test
    @DisplayName("les formats se lisent d'un coup, fichier par fichier, version citée")
    void formatsAreReportedFileByFile() throws Exception {
        mockMvc.perform(get("/api/runner/relay/formats").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows").value(true))
                .andExpect(jsonPath("$.macosAarch64").value(true))
                .andExpect(jsonPath("$.linuxX64").value(true))
                .andExpect(jsonPath("$.license").value(true))
                .andExpect(jsonPath("$.version").value("v0.11.0"));

        Files.delete(macosPath);
        Files.delete(linuxPath);

        // L'écran masque un lien absent au lieu d'en offrir un mort — encore faut-il qu'il puisse le
        // savoir sans déclencher 21 Mo de téléchargement.
        mockMvc.perform(get("/api/runner/relay/formats").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows").value(true))
                .andExpect(jsonPath("$.macosAarch64").value(false))
                .andExpect(jsonPath("$.linuxX64").value(false))
                .andExpect(jsonPath("$.license").value(true));
    }
}
