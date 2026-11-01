package fr.claudegateway.jarvis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MvcResult;

/**
 * Le compagnon Android servi par le chemin des paquets runner (F-232 / SF-232-02) — APK signé par le
 * certificat attendu : routes publiques (aucun JWT), octets et en-têtes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCompanionDownloadSignedApiIntegrationTest {

    private static Path apkPath;
    private static String fingerprint;

    @DynamicPropertySource
    static void apk(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("jarvis-companion-test");
        Path store = SignedApkFixture.keystore(dir, "jarvis");
        fingerprint = SignedApkFixture.fingerprint(store, "jarvis");
        apkPath = SignedApkFixture.signed(dir, "jarvis.apk", store, "jarvis");
        registry.add("app.jarvis.companion.apk-path", apkPath::toString);
        registry.add("app.jarvis.companion.version-code", () -> "4");
        registry.add("app.jarvis.companion.version-name", () -> "0.4.0");
        registry.add("app.jarvis.companion.signing-cert-sha256", () -> fingerprint);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void versionPublique() throws Exception {
        mockMvc.perform(get("/api/runner/download/android/version").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.versionCode").value(4))
                .andExpect(jsonPath("$.versionName").value("0.4.0"))
                .andExpect(jsonPath("$.signingCertSha256").value(fingerprint))
                .andExpect(jsonPath("$.sizeBytes").value(Files.size(apkPath)));
    }

    @Test
    void telechargementPublic_servantLesOctets() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/runner/download/android").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.android.package-archive"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"jarvis-compagnon.apk\""))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(Files.readAllBytes(apkPath));
    }
}
