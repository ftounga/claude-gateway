package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Le nettoyage du verrou de profil (F-122 / SF-122-08) — retire les fichiers singleton résiduels
 * d'une fermeture sale, best-effort, sans jamais lever.
 */
class ChromeProfileLockTest {

    @Test
    @DisplayName("retire SingletonLock / SingletonSocket / SingletonCookie présents")
    void removesSingletonFiles(@TempDir Path profile) throws Exception {
        for (String name : ChromeProfileLock.SINGLETON_FILES) {
            Files.writeString(profile.resolve(name), "stale");
        }

        boolean cleared = ChromeProfileLock.clearStale(profile, null);

        assertTrue(cleared, "un verrou résiduel doit être signalé comme nettoyé");
        for (String name : ChromeProfileLock.SINGLETON_FILES) {
            assertFalse(Files.exists(profile.resolve(name)), name + " doit être retiré");
        }
    }

    @Test
    @DisplayName("aucun fichier singleton : ne nettoie rien, ne lève pas")
    void noFilesIsNoop(@TempDir Path profile) {
        assertFalse(ChromeProfileLock.clearStale(profile, null),
                "sans verrou résiduel, rien à nettoyer");
    }

    @Test
    @DisplayName("profil nul : best-effort, rend false sans lever")
    void nullProfileIsSafe() {
        assertFalse(ChromeProfileLock.clearStale(null, null));
    }

    @Test
    @DisplayName("un seul fichier présent est retiré (nettoyage partiel)")
    void removesWhatIsPresent(@TempDir Path profile) throws Exception {
        Files.writeString(profile.resolve("SingletonLock"), "stale");

        assertTrue(ChromeProfileLock.clearStale(profile, null));
        assertFalse(Files.exists(profile.resolve("SingletonLock")));
    }
}
