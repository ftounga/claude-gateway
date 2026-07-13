package fr.claudegateway.atelier.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires du stockage en mémoire : suppression d'une clé <b>exacte</b> sans collision de
 * préfixe (SF-28-14).
 */
class InMemoryWorkspaceStorageTest {

    private final InMemoryWorkspaceStorage storage = new InMemoryWorkspaceStorage();

    @Test
    void deleteFileRemovesOnlyTheExactKey() {
        storage.putFile("atelier/u/w/x.js", "a".getBytes(StandardCharsets.UTF_8), "text/plain");
        storage.putFile("atelier/u/w/x.js.bak", "b".getBytes(StandardCharsets.UTF_8), "text/plain");

        storage.deleteFile("atelier/u/w/x.js");

        // La clé voisine partageant le préfixe est conservée : deleteFile ≠ deletePrefix.
        assertThat(storage.getFile("atelier/u/w/x.js")).isEmpty();
        assertThat(storage.getFile("atelier/u/w/x.js.bak")).isPresent();
    }

    @Test
    void deleteFileIsNoOpForUnknownKey() {
        storage.putFile("atelier/u/w/a.txt", "a".getBytes(StandardCharsets.UTF_8), "text/plain");

        storage.deleteFile("atelier/u/w/ghost.txt");

        assertThat(storage.getFile("atelier/u/w/a.txt")).isPresent();
    }
}
