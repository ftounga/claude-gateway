package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le chemin Windows avalé par le shell (F-38 / SF-38-23).
 *
 * <p>Rencontré chez un client : {@code --workspace C:\\Users\\moi\\projet} tapé sous Git Bash
 * arrive au runner en {@code C:Usersmoiprojet}. Le runner n'avait rien fait de faux, mais son
 * message affichait un chemin résolu que l'utilisateur n'avait jamais tapé.</p>
 */
class RunnerConfigWindowsPathTest {

    @Test
    @DisplayName("une lettre de lecteur sans séparateur est expliquée")
    void aDriveLetterWithoutSeparatorIsExplained() {
        String hint = RunnerConfig.swallowedSeparatorsHint("C:UsersU66YA96devcagip");

        assertFalse(hint.isEmpty(), "le symptôme doit être nommé");
        assertTrue(hint.contains("perdu ses séparateurs"), hint);
        assertTrue(hint.contains("Git Bash"), hint);
        // D3 : les deux sorties, parce que l'une survit au copier-coller entre shells et l'autre
        // est celle que l'écran propose.
        assertTrue(hint.contains("guillemets"), hint);
        assertTrue(hint.contains("C:/Users/.../projet"), hint);
        // Le chemin RÉELLEMENT reçu est cité : c'est lui qui rend l'explication vérifiable.
        assertTrue(hint.contains("C:UsersU66YA96devcagip"), hint);
    }

    @Test
    @DisplayName("un chemin Windows correct ne déclenche aucune explication")
    void aWellFormedWindowsPathSaysNothing() {
        // Ces chemins peuvent être absents — c'est le message d'origine qui doit le dire, sans une
        // piste hors sujet sur les échappements du shell.
        assertEquals("", RunnerConfig.swallowedSeparatorsHint("C:\\Users\\moi\\projet"));
        assertEquals("", RunnerConfig.swallowedSeparatorsHint("C:/Users/moi/projet"));
    }

    @Test
    @DisplayName("un chemin Unix ne déclenche aucune explication")
    void aUnixPathSaysNothing() {
        assertEquals("", RunnerConfig.swallowedSeparatorsHint("/home/moi/projet"));
        assertEquals("", RunnerConfig.swallowedSeparatorsHint("./projet"));
        assertEquals("", RunnerConfig.swallowedSeparatorsHint(null));
        assertEquals("", RunnerConfig.swallowedSeparatorsHint(""));
    }
}
