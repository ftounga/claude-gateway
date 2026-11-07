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
    @DisplayName("purge : plus de 7 jours")
    void purge() {
        when(repository.deleteOlderThan(any())).thenReturn(3);
        assertThat(service.purge()).isEqualTo(3);
        verify(repository).deleteOlderThan(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(7));
    }
}
