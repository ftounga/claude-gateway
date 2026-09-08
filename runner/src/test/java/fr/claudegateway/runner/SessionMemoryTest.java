package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mémoire de reprise (F-46 / SF-46-01) : ce que le runner retient d'un appairage réussi pour
 * redémarrer sans argument — et ce qu'il ne retient <b>surtout pas</b>.
 */
class SessionMemoryTest {

    @TempDir
    Path workspace;

    @TempDir
    Path home;

    private SessionMemory memory(Path root) {
        return new SessionMemory("https://portal.example.com/api", root.toString(),
                OffsetDateTime.now());
    }

    @Test
    @DisplayName("l'appairage écrit la mémoire dans le projet ET sous le compte")
    void remembers_in_both_places() {
        List<Path> written = SessionMemory.remember(memory(workspace), workspace, home);

        assertEquals(2, written.size(), "les deux emplacements doivent être écrits : " + written);
        assertTrue(Files.exists(SessionMemory.fileIn(workspace)));
        assertTrue(Files.exists(SessionMemory.fileIn(home)));
    }

    @Test
    @DisplayName("aller-retour : ce qui est écrit est relu à l'identique")
    void round_trip() {
        SessionMemory.remember(memory(workspace), workspace, home);

        Optional<SessionMemory.Located> located = SessionMemory.locate(workspace, home);

        assertTrue(located.isPresent());
        assertEquals("https://portal.example.com/api", located.get().memory().gateway());
        assertEquals(workspace.toString(), located.get().recordedRoot());
        assertEquals(workspace, located.get().resolveRoot().orElseThrow());
    }

    @Test
    @DisplayName("la mémoire ne porte aucun secret")
    void carries_no_secret() throws IOException {
        // Le jeton est écrit à côté, par TokenStore ; la mémoire, elle, est copiée sous ~ pour que
        // le double-clic du lanceur (SF-46-02) fonctionne. Cette copie n'est acceptable que parce
        // qu'elle ne contient rien de sensible : c'est ce que ce test verrouille.
        new TokenStore(workspace, home)
                .save(new StoredToken("SECRET-JETON-OPAQUE", java.util.UUID.randomUUID(),
                        OffsetDateTime.now().plusDays(30)));
        SessionMemory.remember(memory(workspace), workspace, home);

        for (Path file : List.of(SessionMemory.fileIn(workspace), SessionMemory.fileIn(home))) {
            String content = Files.readString(file);
            assertFalse(content.contains("SECRET-JETON-OPAQUE"),
                    "la mémoire de reprise ne doit contenir aucun jeton : " + file);
            assertFalse(content.toLowerCase().contains("\"token\""),
                    "aucun champ de jeton ne doit apparaître : " + content);
        }
    }

    @Test
    @DisplayName("le runner lancé depuis un sous-dossier retrouve la mémoire du projet")
    void locates_from_a_subdirectory() throws IOException {
        SessionMemory.remember(memory(workspace), workspace, home);
        Path deep = Files.createDirectories(workspace.resolve("src/main/java"));

        Optional<SessionMemory.Located> located = SessionMemory.locate(deep, home);

        assertTrue(located.isPresent());
        assertTrue(located.get().fromWorkspace());
        assertEquals(SessionMemory.fileIn(workspace), located.get().file());
    }

    @Test
    @DisplayName("hors de tout projet, le repli sous le compte prend le relais")
    void falls_back_to_home(@TempDir Path elsewhere) {
        SessionMemory.remember(memory(workspace), null, home);

        Optional<SessionMemory.Located> located = SessionMemory.locate(elsewhere, home);

        assertTrue(located.isPresent());
        assertFalse(located.get().fromWorkspace());
        assertEquals(workspace, located.get().resolveRoot().orElseThrow());
    }

    @Test
    @DisplayName("la mémoire du projet l'emporte sur celle du compte")
    void workspace_memory_wins(@TempDir Path other) {
        SessionMemory.remember(
                new SessionMemory("https://autre.example.com/api", other.toString(),
                        OffsetDateTime.now()),
                null, home);
        SessionMemory.remember(memory(workspace), workspace, null);

        SessionMemory.Located located = SessionMemory.locate(workspace, home).orElseThrow();

        assertEquals("https://portal.example.com/api", located.memory().gateway());
    }

    @Test
    @DisplayName("une mémoire corrompue est traitée comme absente")
    void corrupted_memory_is_ignored() throws IOException {
        Path file = SessionMemory.fileIn(workspace);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ ceci n'est pas du json");

        assertTrue(SessionMemory.locate(workspace, home).isEmpty());
    }

    @Test
    @DisplayName("une mémoire sans passerelle ne vaut pas mémoire")
    void memory_without_gateway_is_ignored() throws IOException {
        Path file = SessionMemory.fileIn(workspace);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"workspaceRoot\":\"" + workspace + "\"}");

        assertTrue(SessionMemory.locate(workspace, home).isEmpty());
    }

    @Test
    @DisplayName("un projet déplacé avec son .claude-runner reste reprenable")
    void moved_project_still_resolves() {
        SessionMemory.remember(
                new SessionMemory("https://portal.example.com/api",
                        workspace.resolve("ancien-emplacement-disparu").toString(),
                        OffsetDateTime.now()),
                workspace, null);

        SessionMemory.Located located = SessionMemory.locate(workspace, home).orElseThrow();

        assertEquals(workspace, located.resolveRoot().orElseThrow(),
                "la racine doit replier sur le dossier qui porte le .claude-runner");
    }

    @Test
    @DisplayName("une racine disparue et une mémoire de compte : rien à résoudre, on refuse")
    void home_memory_with_missing_root_resolves_to_nothing(@TempDir Path elsewhere) {
        SessionMemory.remember(
                new SessionMemory("https://portal.example.com/api",
                        home.resolve("projet-supprime").toString(), OffsetDateTime.now()),
                null, home);

        SessionMemory.Located located = SessionMemory.locate(elsewhere, home).orElseThrow();

        assertTrue(located.resolveRoot().isEmpty(),
                "aucune racine ne doit être devinée : le refus dira quoi faire");
    }

    @Test
    @DisplayName("le fichier reçoit des permissions restreintes sur POSIX")
    void restricts_permissions() throws IOException {
        SessionMemory.remember(memory(workspace), workspace, home);
        Path file = SessionMemory.fileIn(workspace);
        if (!Files.getFileStore(file).supportsFileAttributeView("posix")) {
            return; // Windows : les ACL héritées s'appliquent.
        }

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
    }

    @Test
    @DisplayName("une écriture impossible ne fait pas échouer l'appairage")
    void unwritable_target_is_best_effort(@TempDir Path readOnly) throws IOException {
        Path blocked = readOnly.resolve("bloque");
        // Un fichier là où l'on attend un dossier : createDirectories échouera, sans exception ici.
        Files.writeString(blocked, "occupé");

        List<Path> written = SessionMemory.remember(memory(workspace), blocked, null);

        assertTrue(written.isEmpty(), "aucun fichier écrit, et surtout aucune exception");
    }
}
