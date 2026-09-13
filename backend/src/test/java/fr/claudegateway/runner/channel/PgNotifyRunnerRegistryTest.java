package fr.claudegateway.runner.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.RunnerLiveness;

/**
 * Ré-annonce de présence du registre de production (F-97 / SF-97-01) : une socket muette ne doit plus
 * entretenir sa propre présence chez les pods pairs.
 *
 * <p>Le thread d'écoute et le planificateur ne sont pas démarrés ({@code @PostConstruct} non appelé) :
 * seule la décision « annoncer ou non » est observée, au niveau des {@code pg_notify} émis.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PgNotifyRunnerRegistryTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement statement;
    @Mock
    private RunnerLiveness liveness;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<JsonNode> notified = new CopyOnWriteArrayList<>();
    private PgNotifyRunnerRegistry registry;

    private final UUID userId = UUID.randomUUID();
    private final UUID liveHost = UUID.randomUUID();
    private final UUID silentHost = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        doAnswer(invocation -> {
            notified.add(objectMapper.readTree((String) invocation.getArgument(1)));
            return null;
        }).when(statement).setString(eq(2), anyString());
        registry = new PgNotifyRunnerRegistry(dataSource, objectMapper, "10.0.0.1", 8081, 15_000L,
                45_000L, liveness);
    }

    private RunnerConnection connectionOf(UUID hostId) {
        return new RunnerConnection(hostId, userId, UUID.randomUUID(), "node", OffsetDateTime.now());
    }

    private List<String> announcedHosts() {
        return notified.stream()
                .filter(node -> "CONNECT".equals(node.path("event").asText()))
                .map(node -> node.path("hostId").asText())
                .toList();
    }

    @Test
    void aConnectionThatStoppedBeatingIsNoLongerReannounced() {
        registry.register(connectionOf(liveHost));
        registry.register(connectionOf(silentHost));
        notified.clear();
        when(liveness.isAlive(userId, liveHost)).thenReturn(true);
        when(liveness.isAlive(userId, silentHost)).thenReturn(false);

        registry.announceAndExpire();

        assertThat(announcedHosts()).containsExactly(liveHost.toString());
    }

    @Test
    void registeringAlwaysAnnouncesAConnectionThatIsBeingEstablished() {
        when(liveness.isAlive(userId, liveHost)).thenReturn(false);

        registry.register(connectionOf(liveHost));

        assertThat(announcedHosts()).containsExactly(liveHost.toString());
    }

    @Test
    void anUnreadableHeartbeatKeepsTheAnnouncement() {
        // Le doute annonce : la présence d'avant F-97 plutôt qu'une disparition sur une panne de base.
        registry.register(connectionOf(liveHost));
        notified.clear();
        when(liveness.isAlive(userId, liveHost)).thenThrow(new IllegalStateException("base"));

        registry.announceAndExpire();

        assertThat(announcedHosts()).containsExactly(liveHost.toString());
    }
}
