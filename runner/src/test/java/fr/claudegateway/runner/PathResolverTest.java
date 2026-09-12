package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Résolution des chemins après le retrait du confinement (F-73 / SF-73-01).
 *
 * <p>Ces tests remplacent {@code PathGuardTest}, dont chaque cas affirmait l'inverse : ils
 * vérifient désormais qu'un chemin qui remonte, un chemin absolu, un {@code ~} ou un lien
 * symbolique sortant sont <b>acceptés</b>. Ce que la suite garde, ce sont les bornes de forme —
 * les seules qui subsistent.</p>
 */
class PathResolverTest {

    @TempDir
    Path root;

    @Test
    void resoutUnCheminRelatifSousLaRacine() {
        PathResolver resolver = new PathResolver(root);

        PathResolver.Resolved resolved = resolver.resolve("src/main/App.java");

        assertEquals("src/main/App.java", resolved.display());
        assertTrue(resolved.path().startsWith(resolver.root()));
    }

    @Test
    void normaliseLesSegmentsInutiles() {
        PathResolver resolver = new PathResolver(root);

        assertEquals("a/b.txt", resolver.resolve("./a//b.txt").display());
        assertEquals("a/b.txt", resolver.resolve("a\\b.txt").display());
    }

    @Test
    void accepteUnCheminQuiRemonteHorsRacine() {
        PathResolver resolver = new PathResolver(root);

        PathResolver.Resolved resolved = resolver.resolve("../voisin/pom.xml");

        assertEquals("../voisin/pom.xml", resolved.display());
        assertEquals(root.getParent().resolve("voisin/pom.xml").normalize(), resolved.path());
        assertFalse(resolved.path().startsWith(resolver.root()));
    }

    @Test
    void accepteUnCheminAbsolu() {
        PathResolver resolver = new PathResolver(root);

        PathResolver.Resolved resolved = resolver.resolve("/etc/hosts");

        assertEquals("/etc/hosts", resolved.display());
        assertEquals(Paths.get("/etc/hosts"), resolved.path());
    }

    @Test
    void etendLeTildeAuDossierDuCompte() {
        PathResolver resolver = new PathResolver(root);

        PathResolver.Resolved resolved = resolver.resolve("~/.ssh/config");

        assertEquals(Paths.get(System.getProperty("user.home"), ".ssh/config"), resolved.path());
    }

    @Test
    void suitUnLienSymboliqueQuiSortDeLaRacine(@TempDir Path outside) throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "mot de passe");
        try {
            Files.createSymbolicLink(root.resolve("lien.txt"), secret);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Liens symboliques non supportés sur cette plateforme");
        }
        PathResolver resolver = new PathResolver(root);

        PathResolver.Resolved resolved = resolver.resolve("lien.txt");

        assertEquals(root.resolve("lien.txt").toAbsolutePath().normalize(), resolved.path());
        assertEquals("mot de passe", Files.readString(resolved.path()));
    }

    @Test
    void refuseUnCheminVideOuInvalide() {
        PathResolver resolver = new PathResolver(root);

        assertEquals("invalid_input", assertThrows(ToolException.class, () -> resolver.resolve("")).code());
        assertEquals("invalid_input", assertThrows(ToolException.class, () -> resolver.resolve("   ")).code());
        assertEquals("invalid_input", assertThrows(ToolException.class, () -> resolver.resolve(null)).code());
        assertEquals("invalid_input", assertThrows(ToolException.class, () -> resolver.resolve("a\0b")).code());
        assertEquals("invalid_input", assertThrows(ToolException.class, () -> resolver.resolve("./")).code());
    }

    @Test
    void refuseUnCheminTropLong() {
        PathResolver resolver = new PathResolver(root);
        String trop = "a".repeat(PathResolver.MAX_PATH_LENGTH + 1);

        assertEquals("invalid_input", assertThrows(ToolException.class, () -> resolver.resolve(trop)).code());
    }

    @Test
    void relativiseSousLaRacineEtRendLAbsoluAilleurs() {
        PathResolver resolver = new PathResolver(root);

        assertEquals("a/b.txt", resolver.relativize(resolver.root().resolve("a").resolve("b.txt")));
        Path ailleurs = resolver.root().getParent().resolve("voisin.txt");
        assertEquals(ailleurs.toString().replace('\\', '/'), resolver.relativize(ailleurs));
    }
}
