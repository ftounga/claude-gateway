package fr.claudegateway.runner.diag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.host.RunnerHostService;

/**
 * F-132 / SF-132-02 — le service de journal de diagnostic : ingestion best-effort, anneau borné,
 * purge TTL, lecture bornée et filtrée.
 */
@ExtendWith(MockitoExtension.class)
class RunnerDiagServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock private RunnerDiagEventRepository repository;
    @Mock private RunnerHostService hostService;
    @Captor private ArgumentCaptor<List<RunnerDiagEventEntity>> rowsCaptor;

    private RunnerDiagService service() {
        return new RunnerDiagService(repository, hostService, mapper);
    }

    private JsonNode frame(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static final UUID USER = UUID.randomUUID();
    private static final UUID HOST = UUID.randomUUID();

    @Test
    void ingest_persists_each_event_with_session_identity() throws Exception {
        JsonNode frame = frame("""
                {"type":"runner_diag","events":[
                  {"ts":"2026-09-19T10:00:00Z","level":"INFO","cat":"chrome","code":"chrome_state",
                   "fields":{"state":"REACHABLE","port":9222}},
                  {"level":"WARN","cat":"teams","code":"session_state","msg":"reconnexion"}
                ]}""");

        service().ingest(USER, HOST, frame);

        verify(repository).saveAll(rowsCaptor.capture());
        List<RunnerDiagEventEntity> rows = rowsCaptor.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getUserId()).isEqualTo(USER);
            assertThat(r.getHostId()).isEqualTo(HOST);
        });
        assertThat(rows.get(0).getLevel()).isEqualTo("INFO");
        assertThat(rows.get(0).getCategory()).isEqualTo("chrome");
        assertThat(rows.get(0).getFields()).contains("REACHABLE").contains("9222");
        assertThat(rows.get(0).getObservedAt()).isNotNull();
        assertThat(rows.get(1).getLevel()).isEqualTo("WARN");
        assertThat(rows.get(1).getMessage()).isEqualTo("reconnexion");
    }

    @Test
    void ingest_level_defaults_to_info_when_missing_or_unknown() throws Exception {
        JsonNode frame = frame("""
                {"events":[{"cat":"vigie","code":"tick","level":"NONSENSE"}]}""");

        service().ingest(USER, HOST, frame);

        verify(repository).saveAll(rowsCaptor.capture());
        assertThat(rowsCaptor.getValue().get(0).getLevel()).isEqualTo("INFO");
    }

    @Test
    void ingest_is_best_effort_on_malformed_frame() throws Exception {
        service().ingest(USER, HOST, frame("{\"events\":[]}"));
        service().ingest(USER, HOST, frame("{\"nothing\":true}"));
        service().ingest(null, HOST, frame("{\"events\":[{\"cat\":\"x\",\"code\":\"y\"}]}"));

        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void ingest_drops_events_without_category_or_code() throws Exception {
        JsonNode frame = frame("""
                {"events":[
                  {"level":"INFO","code":"orphan"},
                  {"level":"INFO","cat":"chrome","code":"chrome_state"}
                ]}""");

        service().ingest(USER, HOST, frame);

        verify(repository).saveAll(rowsCaptor.capture());
        assertThat(rowsCaptor.getValue()).hasSize(1);
        assertThat(rowsCaptor.getValue().get(0).getCode()).isEqualTo("chrome_state");
    }

    @Test
    void ingest_trims_ring_beyond_capacity() throws Exception {
        when(repository.countByUserIdAndHostId(USER, HOST))
                .thenReturn((long) RunnerDiagService.RING_CAPACITY + 3);
        List<UUID> oldest = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(repository.findOldestIds(eq(USER), eq(HOST), any())).thenReturn(oldest);

        service().ingest(USER, HOST, frame("""
                {"events":[{"cat":"chrome","code":"chrome_state","level":"INFO"}]}"""));

        verify(repository).deleteAllByIdInBatch(oldest);
    }

    @Test
    void ingest_does_not_trim_when_under_capacity() throws Exception {
        when(repository.countByUserIdAndHostId(USER, HOST)).thenReturn(10L);

        service().ingest(USER, HOST, frame("""
                {"events":[{"cat":"chrome","code":"chrome_state","level":"INFO"}]}"""));

        verify(repository, never()).deleteAllByIdInBatch(anyList());
    }

    @Test
    void purge_deletes_older_than_ttl() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-19T12:00:00Z");
        when(repository.deleteOlderThan(any())).thenReturn(4);

        int purged = service().purgeExpired(now);

        assertThat(purged).isEqualTo(4);
        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).deleteOlderThan(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(now.minusDays(RunnerDiagService.TTL_DAYS));
    }

    @Test
    void list_requires_ownership_then_clamps_and_filters() {
        when(repository.findWindow(any(), any(), anyList(), any(), any(), any()))
                .thenReturn(List.of());

        service().list(USER, HOST, RunnerDiagLevel.WARN, null, null, 9999);

        verify(hostService).requireOwned(USER, HOST); // isolation d'abord
        ArgumentCaptor<List<String>> levels = ArgumentCaptor.forClass(List.class);
        verify(repository).findWindow(eq(USER), eq(HOST), levels.capture(), any(), any(), any());
        assertThat(levels.getValue()).containsExactlyInAnyOrder("WARN", "ERROR");
    }
}
