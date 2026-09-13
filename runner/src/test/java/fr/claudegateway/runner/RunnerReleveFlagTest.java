package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-100 / SF-100-00 — {@code --releve-teams} est aiguillé avant la configuration : ni gateway, ni
 * racine, ni appairage.
 */
class RunnerReleveFlagTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("--releve-teams sans --gateway ni --root : aucun refus de configuration, le navigateur est cherché")
    void surveyNeedsNoGateway() {
        // Port 1 : rien n'écoute. Le relevé dit « navigateur non détecté » (2) — et n'a pas exigé
        // --gateway : il n'est pas passé par la configuration du runner.
        int code = new RunnerMain(new Console()).execute(
                new String[] { "--releve-teams", "--teams-port", "1", "--sortie", dir.toString() }, Map.of(), dir,
                dir);
        assertEquals(2, code);
    }
}
