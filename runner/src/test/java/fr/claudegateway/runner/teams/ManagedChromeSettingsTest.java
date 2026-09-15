package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.OperatingSystem;

/** F-122 / SF-122-01 — les réglages du Chrome managé : défauts sûrs, tous surchargeables. */
class ManagedChromeSettingsTest {

    private static Function<String, String> env(Map<String, String> values) {
        return values::get;
    }

    @Test
    @DisplayName("Défauts sûrs : port 9222 et dossier de profil par OS")
    void safe_defaults() {
        ManagedChromeSettings settings = ManagedChromeSettings.resolve(null, null,
                OperatingSystem.LINUX, env(Map.of("HOME", "/home/u")));

        assertEquals(BrowserPort.DEFAULT_PORT, settings.port());
        assertTrue(settings.profileDir().toString().contains(".config/claude-gateway/chrome-teams"),
                settings.profileDir().toString());
    }

    @Test
    @DisplayName("Le port vient de l'argument, puis de l'environnement, puis du défaut")
    void port_resolution() {
        assertEquals(9444, ManagedChromeSettings.resolve("9444", null, OperatingSystem.LINUX,
                name -> null).port());
        assertEquals(9555, ManagedChromeSettings.resolve(null, null, OperatingSystem.LINUX,
                env(Map.of(BrowserPort.ENV, "9555"))).port());
    }

    @Test
    @DisplayName("Le dossier de profil est surchargeable par argument")
    void profile_override_by_argument() {
        ManagedChromeSettings settings = ManagedChromeSettings.resolve(null, "/data/profil",
                OperatingSystem.LINUX, name -> null);

        assertEquals(Path.of("/data/profil"), settings.profileDir());
    }

    @Test
    @DisplayName("Le dossier de profil est surchargeable par environnement")
    void profile_override_by_env() {
        ManagedChromeSettings settings = ManagedChromeSettings.resolve(null, null,
                OperatingSystem.LINUX, env(Map.of(ChromePaths.PROFILE_ENV, "/env/profil")));

        assertEquals(Path.of("/env/profil"), settings.profileDir());
    }
}
