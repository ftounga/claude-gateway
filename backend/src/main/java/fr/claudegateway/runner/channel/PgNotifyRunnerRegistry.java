package fr.claudegateway.runner.channel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Registre runner de <b>production</b> (F-38 / SF-38-02, décision D8 / ADR-016). Chaque replica tient
 * ses connexions locales et diffuse les événements connect/disconnect sur le canal Postgres
 * {@code runner_presence} via {@code NOTIFY}. Un thread dédié écoute (via {@code LISTEN}) les
 * événements des <b>autres</b> replicas et maintient une carte de présence distante. Ainsi
 * {@link #isConnected} est correct entre les 2 pods sans composant d'infra supplémentaire (pas de
 * Redis dans la stack). Le <b>relais</b> des messages vers la socket d'un pod distant est SF-38-05 :
 * {@link #findLocal} ne renvoie que la connexion hébergée par ce nœud.
 *
 * <p>Sélectionné quand {@code app.runner.registry=pg-notify}. En dev/tests, c'est
 * {@link InMemoryRunnerRegistry} qui est actif (un seul pod).</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.runner", name = "registry", havingValue = "pg-notify")
public class PgNotifyRunnerRegistry implements RunnerRegistry {

    private static final Logger log = LoggerFactory.getLogger(PgNotifyRunnerRegistry.class);
    private static final String CHANNEL = "runner_presence";
    private static final long POLL_TIMEOUT_MS = 10_000L;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String nodeId = UUID.randomUUID().toString();

    /** Connexions dont la socket vit sur CE nœud. */
    private final Map<UUID, RunnerConnection> local = new ConcurrentHashMap<>();
    /** Présence signalée par les AUTRES nœuds : workspaceId -> nodeId émetteur. */
    private final Map<UUID, String> remote = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread listener;

    public PgNotifyRunnerRegistry(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void start() {
        running.set(true);
        listener = new Thread(this::listenLoop, "runner-presence-listener");
        listener.setDaemon(true);
        listener.start();
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (listener != null) {
            listener.interrupt();
        }
    }

    @Override
    public void register(RunnerConnection connection) {
        local.put(connection.workspaceId(), connection);
        notifyPresence("CONNECT", connection.workspaceId());
    }

    @Override
    public void unregister(UUID workspaceId, UUID tokenId) {
        RunnerConnection[] removed = new RunnerConnection[1];
        local.computeIfPresent(workspaceId, (ws, current) -> {
            if (current.tokenId().equals(tokenId)) {
                removed[0] = current;
                return null;
            }
            return current;
        });
        if (removed[0] != null) {
            notifyPresence("DISCONNECT", workspaceId);
        }
    }

    @Override
    public Optional<RunnerConnection> findLocal(UUID workspaceId) {
        return Optional.ofNullable(local.get(workspaceId));
    }

    @Override
    public boolean isConnected(UUID workspaceId) {
        return local.containsKey(workspaceId) || remote.containsKey(workspaceId);
    }

    /** Diffuse un événement de présence aux autres replicas. */
    private void notifyPresence(String event, UUID workspaceId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("event", event);
        payload.put("workspaceId", workspaceId.toString());
        payload.put("nodeId", nodeId);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
            ps.setString(1, CHANNEL);
            ps.setString(2, objectMapper.writeValueAsString(payload));
            ps.execute();
        } catch (Exception e) {
            // La présence distante est un confort : une NOTIFY perdue dégrade sans casser (le statut
            // reste couvert par la fraicheur de last_seen_at cote service). On journalise, sans lever.
            log.warn("NOTIFY {} du canal runner echoue: {}", event, e.getMessage());
        }
    }

    /** Boucle d'écoute (thread dédié) : applique les événements des autres nœuds à la carte distante. */
    private void listenLoop() {
        while (running.get()) {
            try (Connection conn = dataSource.getConnection()) {
                try (Statement st = conn.createStatement()) {
                    st.execute("LISTEN " + CHANNEL);
                }
                PGConnection pg = conn.unwrap(PGConnection.class);
                while (running.get() && !conn.isClosed()) {
                    PGNotification[] notifications = pg.getNotifications((int) POLL_TIMEOUT_MS);
                    if (notifications != null) {
                        for (PGNotification n : notifications) {
                            applyNotification(n.getParameter());
                        }
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("Ecoute du canal runner interrompue, reprise dans {}s: {}",
                            RETRY_DELAY.toSeconds(), e.getMessage());
                    sleepQuietly(RETRY_DELAY);
                }
            }
        }
    }

    private void applyNotification(String rawPayload) {
        try {
            JsonNode node = objectMapper.readTree(rawPayload);
            String emitter = node.path("nodeId").asText(null);
            if (emitter == null || emitter.equals(nodeId)) {
                return; // Nos propres événements ne modifient pas la carte distante.
            }
            UUID workspaceId = UUID.fromString(node.path("workspaceId").asText());
            String event = node.path("event").asText();
            if ("CONNECT".equals(event)) {
                remote.put(workspaceId, emitter);
            } else if ("DISCONNECT".equals(event)) {
                remote.remove(workspaceId, emitter);
            }
        } catch (Exception e) {
            log.warn("Notification runner illisible ignoree: {}", e.getMessage());
        }
    }

    private static void sleepQuietly(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
