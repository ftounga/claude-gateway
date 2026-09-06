package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Détection des droits (F-38 / SF-38-18). Elle informe, elle ne protège pas — et surtout, elle ne
 * doit jamais empêcher le runner de démarrer.
 */
class PrivilegesTest {

    @Test
    void readsTheRealUidFromProcSelfStatus() {
        String root = "Name:\tjava\nUid:\t0\t0\t0\t0\nGid:\t0\t0\t0\t0\n";
        String user = "Name:\tjava\nUid:\t1000\t1000\t1000\t1000\n";

        assertTrue(Privileges.elevatedFrom(root, "francky"));
        assertFalse(Privileges.elevatedFrom(user, "root"));
    }

    @Test
    void theRealUidWinsOverTheAccountName() {
        // Un `-Duser.name=root` ne rend pas root, et un compte nommé autrement peut l'être.
        assertTrue(Privileges.elevatedFrom("Uid:\t0\t0\t0\t0\n", "quelquun"));
        assertFalse(Privileges.elevatedFrom("Uid:\t1000\t1000\t1000\t1000\n", "root"));
    }

    @Test
    void fallsBackToTheAccountNameWhenProcIsAbsent() {
        // macOS, Windows : pas de /proc. Une approximation vaut mieux qu'un silence sur un poste
        // où l'on est effectivement root.
        assertTrue(Privileges.elevatedFrom(null, "root"));
        assertFalse(Privileges.elevatedFrom(null, "francky"));
        assertTrue(Privileges.elevatedFrom("   ", "root"));
    }

    @Test
    void malformedContentIsNeverElevatedAndNeverThrows() {
        assertFalse(Privileges.elevatedFrom("Uid:\tpasunentier\n", "francky"));
        assertFalse(Privileges.elevatedFrom("Uid:\n", "francky"));
        assertFalse(Privileges.elevatedFrom("aucune ligne utile\n", "francky"));
    }

    @Test
    void detectionNeverThrowsWhateverTheSystem() {
        assertDoesNotThrow(Privileges::detect);
        assertNotNull(Privileges.detect().userName());
    }
}
