package fr.claudegateway.runner.diag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.runner.relay.RunnerRelayBroadcaster;

/**
 * F-132 / SF-132-05 — le réglage du niveau de diagnostic : isolation d'abord, bonne trame,
 * bornage des minutes, remise via le canal local puis la diffusion cross-pod.
 */
@ExtendWith(MockitoExtension.class)
class RunnerDiagControlServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock private RunnerHostService hostService;
    @Mock private RunnerCallDispatcher dispatcher;
    @Mock private RunnerRelayBroadcaster broadcaster;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID HOST = UUID.randomUUID();

    private RunnerDiagControlService service() {
        return new RunnerDiagControlService(hostService, dispatcher, broadcaster, mapper);
    }

    @Test
    void requires_ownership_and_sends_a_debug_control_frame() throws Exception {
        when(dispatcher.sendControl(eq(HOST), anyString())).thenReturn(true);

        RunnerDiagControlService.Outcome outcome = service().enableDebug(USER, HOST, 10);

        verify(hostService).requireOwned(USER, HOST); // isolation d'abord
        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(dispatcher).sendControl(eq(HOST), frame.capture());
        JsonNode sent = mapper.readTree(frame.getValue());
        assertThat(sent.path("type").asText()).isEqualTo("runner_diag_level");
        assertThat(sent.path("level").asText()).isEqualTo("DEBUG");
        assertThat(sent.path("ttlSeconds").asLong()).isEqualTo(600L);
        assertThat(outcome.delivered()).isTrue();
        assertThat(outcome.minutes()).isEqualTo(10);
        // Remis en local : aucune diffusion cross-pod nécessaire.
        verify(broadcaster, never()).broadcastControl(any(), anyString());
    }

    @Test
    void falls_back_to_cross_pod_broadcast_when_not_local() {
        when(dispatcher.sendControl(eq(HOST), anyString())).thenReturn(false);
        when(broadcaster.broadcastControl(eq(HOST), anyString())).thenReturn(true);

        assertThat(service().enableDebug(USER, HOST, 5).delivered()).isTrue();
        verify(broadcaster).broadcastControl(eq(HOST), anyString());
    }

    @Test
    void reports_not_delivered_when_runner_is_offline() {
        when(dispatcher.sendControl(eq(HOST), anyString())).thenReturn(false);
        when(broadcaster.broadcastControl(eq(HOST), anyString())).thenReturn(false);

        assertThat(service().enableDebug(USER, HOST, 10).delivered()).isFalse();
    }

    @Test
    void clamps_minutes_and_defaults_when_absent() throws Exception {
        when(dispatcher.sendControl(eq(HOST), anyString())).thenReturn(true);

        assertThat(service().enableDebug(USER, HOST, null).minutes())
                .isEqualTo(RunnerDiagControlService.DEFAULT_MINUTES);
        assertThat(service().enableDebug(USER, HOST, 9999).minutes())
                .isEqualTo(RunnerDiagControlService.MAX_MINUTES);
        assertThat(service().enableDebug(USER, HOST, 0).minutes()).isEqualTo(1);
    }
}
