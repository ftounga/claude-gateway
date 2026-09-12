package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-80 / SF-80-02 — le drapeau {@code --no-system-trust}, et le module sans lequel la subfeature ne
 * servirait qu'aux lancements par {@code .jar}.
 *
 * <p>Le <b>défaut</b> est le cœur de la décision du PO (OQ-17, tranchée le 2026-09-12) : confiance
 * au magasin du système <b>automatique</b>. Un défaut inversé par accident ferait échouer tout
 * premier lancement en entreprise — exactement ce que cette feature existe pour supprimer.</p>
 */
class SystemTrustFlagTest {

    @TempDir
    Path root;

    private RunnerConfig resolve(String... extra) {
        String[] base = { "--gateway", "https://portal.exemple.fr/api", "--root", root.toString() };
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return RunnerConfig.resolve(args, Map.of());
    }

    @Test
    @DisplayName("par defaut, la confiance au magasin du systeme est ACTIVE (OQ-17)")
    void systemTrustIsOnByDefault() {
        assertTrue(resolve().systemTrust(),
                "OQ-17 : automatique. Un défaut inversé ferait échouer tout premier lancement.");
    }

    @Test
    @DisplayName("--no-system-trust retablit la confiance stricte, seul ou avec une valeur")
    void theFlagRestoresStrictTrust() {
        assertFalse(resolve("--no-system-trust").systemTrust());
        assertFalse(resolve("--no-system-trust=true").systemTrust());
        assertFalse(resolve("--no-system-trust=oui").systemTrust());
        // La forme explicitement fausse ne doit pas restreindre.
        assertTrue(resolve("--no-system-trust=false").systemTrust());
    }

    @Test
    @DisplayName("le drapeau seul n'avale pas l'argument suivant")
    void theBareFlagDoesNotSwallowTheNextArgument() {
        RunnerConfig config = RunnerConfig.resolve(new String[] {
                "--no-system-trust", "--gateway", "https://portal.exemple.fr/api",
                "--root", root.toString(), "--label", "poste-po" }, Map.of());

        assertFalse(config.systemTrust());
        // Si le drapeau avait avalé « --gateway », rien de tout ceci ne tiendrait.
        assertTrue(config.gatewayBaseUrl().equals("https://portal.exemple.fr/api"));
        assertTrue("poste-po".equals(config.label()));
    }

    @Test
    @DisplayName("la variable d'environnement vaut le drapeau")
    void theEnvironmentVariableWorksToo() {
        RunnerConfig config = RunnerConfig.resolve(
                new String[] { "--gateway", "https://portal.exemple.fr/api",
                        "--root", root.toString() },
                Map.of("CLAUDE_RUNNER_NO_SYSTEM_TRUST", "true"));

        assertFalse(config.systemTrust());
    }

    // ------------------------------------------------------------------ les paquets (D5)

    @Test
    @DisplayName("le paquet Windows embarque le module qui expose Windows-ROOT")
    void theWindowsPackageCarriesTheModuleThatExposesTheSystemStore() throws Exception {
        // Les trois paquets embarquent leur PROPRE JVM, donc leur propre cacerts. Sans
        // jdk.crypto.mscapi, KeyStore.getInstance("Windows-ROOT") lève dans l'image, le repli
        // silencieux s'applique, et SF-80-02 ne servirait qu'aux lancements par .jar — c'est-à-dire
        // à personne sur un poste d'entreprise Windows.
        String script = Files.readString(Path.of("package-windows.sh"), StandardCharsets.UTF_8);

        assertTrue(script.contains("jdk.crypto.mscapi"),
                "sans ce module, le magasin de Windows est invisible depuis l'image jlink");
        assertTrue(script.contains("MODULES=\"java.base"), "les modules doivent rester déclarés");
    }

    @Test
    @DisplayName("le paquet macOS dit pourquoi il n'a rien a ajouter")
    void theMacosPackageSaysWhyItNeedsNothing() throws Exception {
        // Le fournisseur Apple (KeychainStore) vit dans java.base sur les builds macOS : rien à
        // ajouter. Ce qui doit être écrit, c'est la RAISON — sans quoi la prochaine lecture du
        // script conclura à un oubli.
        String script = Files.readString(Path.of("package-macos.sh"), StandardCharsets.UTF_8);

        assertTrue(script.contains("SF-80-02"), "la décision doit être tracée dans le script");
        assertTrue(script.contains("KeychainStore"), script.substring(0, Math.min(400, script.length())));
    }
}
