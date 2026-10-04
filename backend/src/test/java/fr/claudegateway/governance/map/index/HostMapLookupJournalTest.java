package fr.claudegateway.governance.map.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Le journal des consultations de la carte (F-174 / SF-174-01). */
class HostMapLookupJournalTest {

    private final HostMapLookupRepository repository = mock(HostMapLookupRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC);
    private final HostMapLookupJournal journal = new HostMapLookupJournal(repository, clock);
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    @Test
    @DisplayName("compte les faits, les caractères, les pièges, les échéances et les fichiers sources")
    void countsWhatWasGiven() {
        String block = """
                Ce que la carte de ce client dit déjà :
                - compte 123456789012 prod  [plateformes.md]
                - le proxy coupe les websockets  [acces.md § Proxy, constaté le 2026-09-01]  ⟨piège⟩
                - jeton GitLab  [acces.md § Jetons]  ⟨échéance 2026-10-09 — dans 5 j⟩
                """;
        journal.record(userId, hostId, null, HostMapLookup.KIND_TURN, HostMapLookup.STRATEGY_LEXICAL,
                block);

        ArgumentCaptor<HostMapLookup> saved = ArgumentCaptor.forClass(HostMapLookup.class);
        verify(repository).save(saved.capture());
        HostMapLookup row = saved.getValue();
        assertThat(row.getUserId()).isEqualTo(userId);
        assertThat(row.getHostId()).isEqualTo(hostId);
        assertThat(row.getKind()).isEqualTo("TURN");
        assertThat(row.getStrategy()).isEqualTo("LEXICAL");
        assertThat(row.getFactsCount()).isEqualTo(3);
        assertThat(row.getChars()).isEqualTo(block.length());
        assertThat(row.getPitfallsCount()).isEqualTo(1);
        assertThat(row.getDeadlinesCount()).isEqualTo(1);
        assertThat(row.getSources()).isEqualTo("plateformes.md,acces.md");
        assertThat(row.getCreatedAt().toInstant()).isEqualTo(Instant.parse("2026-10-04T10:00:00Z"));
    }

    @Test
    @DisplayName("un tour sans fait joint est compté comme NONE, avec zéro")
    void emptyTurnIsCountedAsNone() {
        journal.record(userId, hostId, null, HostMapLookup.KIND_TURN, HostMapLookup.STRATEGY_LEXICAL,
                null);
        ArgumentCaptor<HostMapLookup> saved = ArgumentCaptor.forClass(HostMapLookup.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStrategy()).isEqualTo("NONE");
        assertThat(saved.getValue().getFactsCount()).isZero();
        assertThat(saved.getValue().getSources()).isNull();
    }

    @Test
    @DisplayName("sans utilisateur ou sans poste, rien n'est rangé")
    void nothingWithoutOwner() {
        journal.record(null, hostId, null, "TURN", "LEXICAL", "- x  [a.md]");
        journal.record(userId, null, null, "TURN", "LEXICAL", "- x  [a.md]");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("une base en panne ne remonte jamais au tour")
    void neverThrows() {
        when(repository.save(any())).thenThrow(new IllegalStateException("db"));
        journal.record(userId, hostId, null, "TURN", "LEXICAL", "- x  [a.md]");
    }
}
