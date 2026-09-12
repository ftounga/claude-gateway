package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-80 / SF-80-03 — {@code --check} : le contrôle de vol <b>seul</b>.
 *
 * <p>Le test de référence d'un poste d'entreprise est celui qui emprunte exactement le chemin du
 * runner : sous inspection TLS, {@code curl} répond {@code 200} et le runner échoue. Jusqu'ici ce
 * test coûtait un <b>code d'appairage</b>, qui expire en 5 minutes — donc il coûtait un
 * aller-retour à chaque essai. {@code --check} le rend gratuit et répétable.</p>
 */
class RunnerCheckFlagTest {

    @TempDir
    Path root;

    private RunnerConfig resolve(String... extra) {
        String[] base = { "--gateway", "https://portal.exemple.fr/api" };
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return RunnerConfig.resolve(args, Map.of());
    }

    @Test
    @DisplayName("--check n'est pas actif par defaut")
    void checkIsOffByDefault() {
        assertFalse(resolve("--root", root.toString()).checkOnly());
    }

    @Test
    @DisplayName("--check n'exige pas --root : un controle de vol ne touche aucun fichier")
    void checkDoesNotRequireARoot() {
        RunnerConfig config = resolve("--check");

        assertTrue(config.checkOnly());
        // D3 : le dossier courant fait office de racine. Exiger une racine pour lancer un contrôle
        // de vol ajouterait un obstacle à l'outil censé en retirer un.
        assertEquals(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
                config.hostRoot());
    }

    @Test
    @DisplayName("--check ne desactive AUCUNE validation : une racine fournie est verifiee")
    void checkRelaxesNothingAboutAGivenRoot() {
        // On n'assouplit rien, on n'exige pas. Dès qu'une racine est fournie, elle repasse par les
        // validations ordinaires.
        assertThrows(RunnerConfig.ConfigException.class,
                () -> resolve("--check", "--root", root.resolve("absent").toString()));
    }

    @Test
    @DisplayName("--check sans --gateway reste une erreur d'usage")
    void checkStillNeedsAGateway() {
        assertThrows(RunnerConfig.ConfigException.class,
                () -> RunnerConfig.resolve(new String[] { "--check" }, Map.of()));
    }

    @Test
    @DisplayName("le drapeau seul n'avale pas l'argument suivant")
    void theBareFlagDoesNotSwallowTheNextArgument() {
        RunnerConfig config = RunnerConfig.resolve(new String[] {
                "--check", "--gateway", "https://portal.exemple.fr/api",
                "--root", root.toString() }, Map.of());

        assertTrue(config.checkOnly());
        assertEquals("https://portal.exemple.fr/api", config.gatewayBaseUrl());
    }

    @Test
    @DisplayName("la variable d'environnement vaut le drapeau")
    void theEnvironmentVariableWorksToo() {
        RunnerConfig config = RunnerConfig.resolve(
                new String[] { "--gateway", "https://portal.exemple.fr/api",
                        "--root", root.toString() },
                Map.of("CLAUDE_RUNNER_CHECK", "true"));

        assertTrue(config.checkOnly());
    }

    @Test
    @DisplayName("--check ne consomme aucun code d'appairage : il n'en demande pas")
    void checkNeverConsumesAPairingCode() {
        // C'est la raison d'être du drapeau. Un contrôle de vol qui exigerait un code ne serait pas
        // rejouable : le code expire en 5 minutes.
        RunnerConfig config = resolve("--check");

        org.junit.jupiter.api.Assertions.assertNull(config.pairingCode());
    }
}
