package fr.claudegateway.runner.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.channel.RemoteRunnerNode;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerConnection;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerRegistry;

/**
 * Tests du <b>choix du pod</b> (F-38 / SF-38-12). La règle tient en trois cas et l'ordre compte :
 * local d'abord, relais ensuite, erreur existante en dernier. Aucune panne du relais ne doit inventer
 * un comportement : elle dégrade vers ce qui se passait déjà avant SF-38-12.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunnerCallRouterTest {

    private static final String SELF = "http://10.0.0.1:8081";
    private static final String PEER = "http://10.0.0.2:8081";

    @Mock
    private RunnerRegistry registry;
    @Mock
    private RunnerCallDispatcher dispatcher;
    @Mock
    private RunnerRelayClient relayClient;
    @Mock
    private ObjectProvider<RunnerRelayClient> relayProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID hostId = UUID.randomUUID();
    /** Cible d'un appel (F-48 / SF-48-01) : le poste route, le projet accompagne. */
    private final fr.claudegateway.runner.channel.RunnerTarget target =
            new fr.claudegateway.runner.channel.RunnerTarget(hostId, UUID.randomUUID(), "projet");
    private RunnerRelayProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RunnerRelayProperties();
        properties.setSecret("un-secret-de-relais-de-test-32-octets");
        properties.setSelfAddress("10.0.0.1");
        properties.setPort(8081);
        when(relayProvider.getIfAvailable()).thenReturn(relayClient);
        when(relayProvider.getObject()).thenReturn(relayClient);
    }

    private RunnerCallRouter router() {
        return new RunnerCallRouter(registry, dispatcher, properties, relayProvider);
    }

    private RunnerCallResult ok() {
        return new RunnerCallResult(true, "ok", false, null, 1L, null, null, null, "", false);
    }

    private void localSocketPresent() {
        when(registry.findLocal(hostId)).thenReturn(Optional.of(new RunnerConnection(hostId,
                UUID.randomUUID(), UUID.randomUUID(), "node-1", OffsetDateTime.now())));
    }

    @Test
    void localSocketIsServedLocallyAndNeverRelayed() {
        localSocketPresent();
        when(dispatcher.call(any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(ok());

        RunnerCallResult result = router().call(target, "toolu_1", "list_files",
                objectMapper.createObjectNode(), 30_000L);

        assertThat(result.ok()).isTrue();
        verify(dispatcher).call(eq(target), eq("toolu_1"), eq("list_files"), any(), eq(30_000L),
                any());
        verifyNoInteractions(relayClient);
    }

    @Test
    void remoteAddressIsRelayedExactlyOnce() {
        when(registry.findLocal(hostId)).thenReturn(Optional.empty());
        when(registry.findRemote(hostId))
                .thenReturn(Optional.of(new RemoteRunnerNode("node-2", PEER)));
        when(relayClient.call(any(), any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(ok());

        RunnerCallResult result = router().call(target, "toolu_2", "read_file",
                objectMapper.createObjectNode(), 30_000L);

        assertThat(result.ok()).isTrue();
        verify(relayClient, times(1)).call(any(), eq(target), eq("toolu_2"), eq("read_file"),
                any(), eq(30_000L), any());
        verify(dispatcher, never()).call(any(), anyString(), anyString(), any(), anyLong(), any());
    }

    @Test
    void unknownRemoteAddressDegradesToTheExistingError() {
        when(registry.findLocal(hostId)).thenReturn(Optional.empty());
        when(registry.findRemote(hostId)).thenReturn(Optional.empty());
        when(registry.isConnected(hostId)).thenReturn(false);

        RunnerCallResult result = router().call(target, "toolu_3", "list_files",
                objectMapper.createObjectNode(), 30_000L);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_UNAVAILABLE);
        verifyNoInteractions(relayClient);
    }

    @Test
    void connectedElsewhereWithoutAddressKeepsRunnerNotOnThisNode() {
        when(registry.findLocal(hostId)).thenReturn(Optional.empty());
        when(registry.findRemote(hostId)).thenReturn(Optional.empty());
        when(registry.isConnected(hostId)).thenReturn(true);

        RunnerCallResult result = router().call(target, "toolu_4", "list_files",
                objectMapper.createObjectNode(), 30_000L);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE);
    }

    @Test
    void remoteAddressEqualToOursIsNeverRelayedToItself() {
        when(registry.findLocal(hostId)).thenReturn(Optional.empty());
        when(registry.findRemote(hostId))
                .thenReturn(Optional.of(new RemoteRunnerNode("node-1", SELF)));
        when(registry.isConnected(hostId)).thenReturn(true);

        RunnerCallResult result = router().call(target, "toolu_5", "list_files",
                objectMapper.createObjectNode(), 30_000L);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE);
        verifyNoInteractions(relayClient);
    }

    @Test
    void relayDisabledKeepsTheBehaviourOfBeforeSf3812() {
        properties.setSecret("");
        when(registry.findLocal(hostId)).thenReturn(Optional.empty());
        when(registry.findRemote(hostId))
                .thenReturn(Optional.of(new RemoteRunnerNode("node-2", PEER)));
        when(registry.isConnected(hostId)).thenReturn(true);

        RunnerCallResult result = router().call(target, "toolu_6", "list_files",
                objectMapper.createObjectNode(), 30_000L);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE);
        verifyNoInteractions(relayClient);
    }
}
