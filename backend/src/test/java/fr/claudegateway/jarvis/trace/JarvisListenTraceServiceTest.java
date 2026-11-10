package fr.claudegateway.jarvis.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Le journal de l'écoute (F-233 / SF-233-15) : nettoyage, bornes, plafond quotidien, purge. */
class JarvisListenTraceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T14:00:00Z");

    private JarvisListenTraceRepository repository;
    private JarvisListenTraceService service;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(JarvisListenTraceRepository.class);
        service = new JarvisListenTraceService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private List<JarvisListenTraceEvent> saved() {
        ArgumentCaptor<List<JarvisListenTraceEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("un lot rangé sous le compte : champs nettoyés, compteur gardé, événement invalide ignoré")
    void cleans() {
        int written = service.record(user, new JarvisListenTraceService.Batch("abc-123", "TJGIBB4X", true, List.of(
                new JarvisListenTraceService.Event(1, 120L, "page_load", "repos", "idle", "sw=1 companion=1", null),
                new JarvisListenTraceService.Event(2, -5L, "Bad Event!", "repos", "", "x", 1),
                new JarvisListenTraceService.Event(3, 2_000_000_000L, "mic_fail", "Ecoute!", "active",
                        "NotReadableError \"bonjour\" <script>", 7))));
        assertThat(written).isEqualTo(2);
        List<JarvisListenTraceEvent> rows = saved();
        assertThat(rows).hasSize(2);
        JarvisListenTraceEvent first = rows.get(0);
        assertThat(first.getUserId()).isEqualTo(user);
        assertThat(first.getPageId()).isEqualTo("abc-123");
        assertThat(first.getPageVersion()).isEqualTo("TJGIBB4X");
        assertThat(first.isCompanion()).isTrue();
        assertThat(first.getEvent()).isEqualTo("page_load");
        assertThat(first.getRepeatCount()).isEqualTo(1);
        assertThat(first.getCreatedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        JarvisListenTraceEvent second = rows.get(1);
        assertThat(second.getState()).isNull();
        assertThat(second.getMode()).isEqualTo("active");
        assertThat(second.getAtMs()).isEqualTo(JarvisListenTraceService.MAX_AT_MS);
        assertThat(second.getDetail()).isEqualTo("NotReadableError bonjour script>");
        assertThat(second.getRepeatCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("identifiant de page et version illisibles : remplacés, jamais rejetés")
    void badPageAndVersion() {
        service.record(user, new JarvisListenTraceService.Batch("<x>", "a b c", null,
                List.of(new JarvisListenTraceService.Event(1, 0L, "state", "ecoute", "active", null, null))));
        JarvisListenTraceEvent row = saved().get(0);
        assertThat(row.getPageId()).isEqualTo("?");
        assertThat(row.getPageVersion()).isNull();
        assertThat(row.isCompanion()).isFalse();
    }

    @Test
    @DisplayName("au plus 100 événements par lot ; détail coupé à 120")
    void bounds() {
        List<JarvisListenTraceService.Event> events = new ArrayList<>();
        IntStream.range(0, 150).forEach(i -> events.add(
                new JarvisListenTraceService.Event(i, (long) i, "tick", "ecoute", "active", "y".repeat(300), 1)));
        int written = service.record(user, new JarvisListenTraceService.Batch("p", null, false, events));
        assertThat(written).isEqualTo(JarvisListenTraceService.MAX_EVENTS_PER_BATCH);
        assertThat(saved().get(0).getDetail()).hasSize(120);
    }

    @Test
    @DisplayName("plafond de 20 000 lignes sur 24 h : le lot est ignoré")
    void dailyCap() {
        when(repository.countByUserIdAndCreatedAtAfter(eq(user), any()))
                .thenReturn(JarvisListenTraceService.MAX_ROWS_PER_DAY);
        int written = service.record(user, new JarvisListenTraceService.Batch("p", null, false,
                List.of(new JarvisListenTraceService.Event(1, 0L, "state", null, null, null, null))));
        assertThat(written).isZero();
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("corps vide ou sans événement : rien")
    void empty() {
        assertThat(service.record(user, null)).isZero();
        assertThat(service.record(user, new JarvisListenTraceService.Batch("p", null, false, List.of()))).isZero();
        assertThat(service.record(null, new JarvisListenTraceService.Batch("p", null, false,
                List.of(new JarvisListenTraceService.Event(1, 0L, "state", null, null, null, null))))).isZero();
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("SF-255-01 : heure serveur = réception − (envoi − t) ; tour rangé, tour hors format écarté")
    void serverTimeAndTurn() {
        service.record(user, new JarvisListenTraceService.Batch("p", null, false, List.of(
                new JarvisListenTraceService.Event(1, 4_000L, "listen_end", "ecoute", "active", null, null, "tour-1"),
                new JarvisListenTraceService.Event(2, 12_000L, "transcript", null, null, null, null, "<tour>")),
                10_000L));
        List<JarvisListenTraceEvent> rows = saved();
        OffsetDateTime received = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        assertThat(rows.get(0).getOccurredAt()).isEqualTo(received.minusSeconds(6));
        assertThat(rows.get(0).getTurnId()).isEqualTo("tour-1");
        // Un événement daté après l'envoi est ramené à la réception.
        assertThat(rows.get(1).getOccurredAt()).isEqualTo(received);
        assertThat(rows.get(1).getTurnId()).isNull();
    }

    @Test
    @DisplayName("SF-255-01 : sans heure d'envoi (écran d'avant), aucune heure serveur, aucun tour")
    void legacyBatch() {
        service.record(user, new JarvisListenTraceService.Batch("p", null, false,
                List.of(new JarvisListenTraceService.Event(1, 5L, "state", null, null, null, null))));
        JarvisListenTraceEvent row = saved().get(0);
        assertThat(row.getOccurredAt()).isNull();
        assertThat(row.getTurnId()).isNull();
    }

    @Test
    @DisplayName("SF-255-01 : événement serveur rangé sous le compte, à l'heure exacte ; rien sans tour ni au plafond")
    void serverEvent() {
        assertThat(service.recordServer(user, "page-1", "tour-1", JarvisListenTraceService.SERVER_CONVERSE,
                "subject=none spec=0")).isTrue();
        ArgumentCaptor<JarvisListenTraceEvent> captor = ArgumentCaptor.forClass(JarvisListenTraceEvent.class);
        verify(repository).save(captor.capture());
        JarvisListenTraceEvent row = captor.getValue();
        assertThat(row.getUserId()).isEqualTo(user);
        assertThat(row.getPageId()).isEqualTo("page-1");
        assertThat(row.getTurnId()).isEqualTo("tour-1");
        assertThat(row.getEvent()).isEqualTo("srv_converse");
        assertThat(row.getDetail()).isEqualTo("subject=none spec=0");
        assertThat(row.getOccurredAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));

        assertThat(service.recordServer(user, "page-1", null, "srv_converse", null)).isFalse();
        assertThat(service.recordServer(user, "page-1", "x y", "srv_converse", null)).isFalse();
        when(repository.countByUserIdAndCreatedAtAfter(eq(user), any()))
                .thenReturn(JarvisListenTraceService.MAX_ROWS_PER_DAY);
        assertThat(service.recordServer(user, "page-1", "tour-2", "srv_converse", null)).isFalse();
        verify(repository).save(any());
    }

    @Test
    @DisplayName("purge : plus de 7 jours")
    void purge() {
        when(repository.deleteOlderThan(any())).thenReturn(3);
        assertThat(service.purge()).isEqualTo(3);
        verify(repository).deleteOlderThan(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(7));
    }
}
