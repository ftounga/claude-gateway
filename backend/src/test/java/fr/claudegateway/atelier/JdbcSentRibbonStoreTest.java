package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * F-276 / SF-276-02 — les rubans envoyés en base : rangés, relus et purgés pour leur seul utilisateur et
 * leur seul terminal, et jamais chargés avec le message (l'entité ne porte pas la colonne).
 */
@SpringBootTest
@ActiveProfiles("test")
class JdbcSentRibbonStoreTest {

    @Autowired private JdbcSentRibbonStore store;
    @Autowired private AtelierMessageRepository messages;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private JdbcTemplate jdbc;

    private UUID workspace(UUID userId) {
        return workspaces.save(Workspace.builder().userId(userId).name("ruban-" + UUID.randomUUID())
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build()).getId();
    }

    private UUID message(UUID workspaceId, UUID userId, OffsetDateTime at) {
        AtelierMessage saved = messages.save(AtelierMessage.builder().workspaceId(workspaceId).userId(userId)
                .role("ASSISTANT").content("réponse").build());
        jdbc.update("update atelier_messages set created_at = ? where id = ?",
                java.sql.Timestamp.from(at.toInstant()), saved.getId());
        return saved.getId();
    }

    @Test
    void savesAndLoadsOnlyForItsOwner() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID ws = workspace(alice);
        UUID id = message(ws, alice, OffsetDateTime.now());

        store.save(bob, id, "{\"pirate\":true}");
        assertThat(store.load(alice, List.of(id))).isEmpty();
        store.save(alice, id, "{\"v\":1}");

        assertThat(store.load(alice, List.of(id))).containsEntry(id, "{\"v\":1}");
        assertThat(store.load(bob, List.of(id))).isEmpty();
        assertThat(store.replayEnabled()).isTrue();
    }

    @Test
    void theCompactionPurgesOnlyWhatPrecedesTheFrontierOfThisTerminal() {
        UUID alice = UUID.randomUUID();
        UUID ws = workspace(alice);
        UUID other = workspace(alice);
        OffsetDateTime frontier = OffsetDateTime.parse("2026-11-20T10:00:00Z");
        UUID before = message(ws, alice, frontier.minusHours(1));
        UUID after = message(ws, alice, frontier.plusHours(1));
        UUID elsewhere = message(other, alice, frontier.minusHours(1));
        for (UUID id : List.of(before, after, elsewhere)) {
            store.save(alice, id, "{\"v\":1}");
        }

        assertThat(store.purgeBefore(UUID.randomUUID(), ws, frontier)).isZero();
        assertThat(store.purgeBefore(alice, ws, frontier)).isEqualTo(1);

        assertThat(store.load(alice, List.of(before, after, elsewhere))).containsOnlyKeys(after, elsewhere);
    }
}
