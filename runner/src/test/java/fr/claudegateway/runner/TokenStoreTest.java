package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenStoreTest {

    @TempDir
    Path workspace;

    @TempDir
    Path home;

    private StoredToken token(OffsetDateTime expiresAt) {
        return new StoredToken("opaque-token-value", UUID.randomUUID(), expiresAt);
    }

    @Test
    void saves_under_workspace_when_writable() {
        TokenStore store = new TokenStore(workspace, home);
        assertTrue(store.tokenFile().startsWith(workspace),
                "le jeton doit être stocké sous le workspace quand il est inscriptible");
    }

    @Test
    void save_then_load_round_trip() {
        TokenStore store = new TokenStore(workspace, home);
        StoredToken saved = token(OffsetDateTime.now().plusDays(30));
        store.save(saved);

        Optional<StoredToken> loaded = store.load();
        assertTrue(loaded.isPresent());
        assertEquals(saved.token(), loaded.get().token());
        assertEquals(saved.workspaceId(), loaded.get().workspaceId());
    }

    @Test
    void load_empty_when_no_file() {
        TokenStore store = new TokenStore(workspace, home);
        assertTrue(store.load().isEmpty());
    }

    @Test
    void load_empty_when_expired() {
        TokenStore store = new TokenStore(workspace, home);
        store.save(token(OffsetDateTime.now().minusMinutes(5)));
        assertTrue(store.load().isEmpty(), "un jeton expiré est traité comme absent");
    }

    @Test
    void load_empty_when_corrupt() throws Exception {
        TokenStore store = new TokenStore(workspace, home);
        Files.createDirectories(store.tokenFile().getParent());
        Files.writeString(store.tokenFile(), "{ this is not json");
        assertTrue(store.load().isEmpty());
    }

    @Test
    void clear_removes_file_idempotently() {
        TokenStore store = new TokenStore(workspace, home);
        store.save(token(OffsetDateTime.now().plusDays(1)));
        assertTrue(store.load().isPresent());
        store.clear();
        assertTrue(store.load().isEmpty());
        store.clear(); // idempotent
    }

    @Test
    void file_has_restricted_permissions_on_posix() throws Exception {
        TokenStore store = new TokenStore(workspace, home);
        store.save(token(OffsetDateTime.now().plusDays(1)));
        if (Files.getFileStore(store.tokenFile()).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(store.tokenFile());
            assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
            assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
            assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        }
    }
}
