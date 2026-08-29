package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Confinement des chemins à la racine du runner (F-38 / SF-38-04). C'est la garde de sécurité
 * centrale de la subfeature : tout ce qui n'est pas sous {@code --workspace} doit être refusé, y
 * compris via un lien symbolique.
 */
class PathGuardTest {

    @TempDir
    Path root;

    @Test
    void resoutUnCheminRelatifSousLaRacine() {
        PathGuard guard = new PathGuard(root);

        PathGuard.Resolved resolved = guard.resolve("src/main/App.java");

        assertEquals("src/main/App.java", resolved.relative());
        assertTrue(resolved.path().startsWith(guard.root()));
    }

    @Test
    void normaliseLesSegmentsInutiles() {
        PathGuard guard = new PathGuard(root);

        assertEquals("a/b.txt", guard.resolve("./a//b.txt").relative());
        assertEquals("a/b.txt", guard.resolve("a\\b.txt").relative());
    }

    @Test
    void refuseUnCheminQuiRemonteHorsRacine() {
        PathGuard guard = new PathGuard(root);

        ToolException error = assertThrows(ToolException.class, () -> guard.resolve("../secret.txt"));

        assertEquals("path_outside_root", error.code());
    }

    @Test
    void refuseUnCheminAbsolu() {
        PathGuard guard = new PathGuard(root);

        assertEquals("path_outside_root",
                assertThrows(ToolException.class, () -> guard.resolve("/etc/passwd")).code());
    }

    @Test
    void refuseUneLettreDeLecteurWindows() {
        PathGuard guard = new PathGuard(root);

        assertEquals("path_outside_root",
                assertThrows(ToolException.class, () -> guard.resolve("C:\\Windows\\system.ini")).code());
    }

    @Test
    void refuseUnLienSymboliqueQuiSortDeLaRacine(@TempDir Path outside) throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "mot de passe");
        try {
            Files.createSymbolicLink(root.resolve("lien.txt"), secret);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Liens symboliques non supportés sur cette plateforme");
        }
        PathGuard guard = new PathGuard(root);

        ToolException error = assertThrows(ToolException.class, () -> guard.resolve("lien.txt"));

        assertEquals("path_outside_root", error.code());
    }

    @Test
    void refuseUnCheminVideOuInvalide() {
        PathGuard guard = new PathGuard(root);

        assertEquals("invalid_input", assertThrows(ToolException.class, () -> guard.resolve("")).code());
        assertEquals("invalid_input", assertThrows(ToolException.class, () -> guard.resolve("   ")).code());
        assertEquals("invalid_input", assertThrows(ToolException.class, () -> guard.resolve(null)).code());
        assertEquals("invalid_input", assertThrows(ToolException.class, () -> guard.resolve("a\0b")).code());
        assertEquals("invalid_input", assertThrows(ToolException.class, () -> guard.resolve("./")).code());
    }

    @Test
    void neFuitJamaisLeCheminAbsoluDansLeMessage() {
        PathGuard guard = new PathGuard(root);

        ToolException error = assertThrows(ToolException.class, () -> guard.resolve("../../etc/passwd"));

        assertTrue(!error.getMessage().contains(guard.root().toString()),
                "Le message d'erreur ne doit contenir aucun chemin absolu : " + error.getMessage());
    }

    @Test
    void relativiseAvecUnSeparateurSlash() {
        PathGuard guard = new PathGuard(root);

        String relative = guard.relativize(guard.root().resolve("a").resolve("b.txt"));

        assertEquals("a/b.txt", relative);
    }
}
