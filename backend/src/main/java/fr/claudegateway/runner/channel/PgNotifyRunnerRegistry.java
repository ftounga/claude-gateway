package fr.claudegateway.runner.channel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * {@link #isConnected} est correct entre les pods sans composant d'infra supplémentaire (pas de Redis
 * dans la stack).
 *
 * <p><b>SF-38-12</b> — la présence porte désormais l'<b>adresse</b> du pod émetteur
 * ({@code http://{POD_IP}:8081}, connecteur interne). {@link #findRemote} la rend au
 * {@code RunnerCallRouter}, qui relaie l'appel d'outil au pod propriétaire de la socket. Sans
 * adresse (pod sans {@code POD_IP}, présence non convergée), pas de relais : l'appel dégrade vers
 * l'erreur d'origine, jamais vers un comportement inventé.</p>
 *
 * <p><b>Convergence</b> — trois mécanismes, sans lesquels le relais serait inutile le jour où l'HPA
 * scale : ré-annonce périodique de chaque connexion locale ({@code app.runner.presence.announce-ms}),
 * péremption d'une présence distante trop ancienne ({@code app.runner.presence.stale-after-ms}), et
 * {@code SYNC_REQUEST} émis au démarrage — tout pod qui le reçoit ré-émet ses {@code CONNECT}
 * locaux. Un {@code SYNC_REQUEST} n'est <b>jamais</b> rediffusé : aucune boucle possible. Un
 * événement par connexion, jamais de lot : la charge reste très en dessous des 8 000 octets de
 * {@code pg_notify}.</p>
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

    private static final String EVENT_CONNECT = "CONNECT";
    private static final String EVENT_DISCONNECT = "DISCONNECT";
    private static final String EVENT_SYNC_REQUEST = "SYNC_REQUEST";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String nodeId = UUID.randomUUID().toString();
    /** Adresse du connecteur interne de CE pod, ou {@code ""} si aucune {@code POD_IP} n'est fournie. */
    private final String selfAddress;
    private final long announceMs;
    private final long staleAfterMs;

    /** Connexions dont la socket vit sur CE nœud. */
    private final Map<UUID, RunnerConnection> local = new ConcurrentHashMap<>();
    /** Présence signalée par les AUTRES nœuds. */
    private final Map<UUID, RemotePresence> remote = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread listener;
    private ScheduledExecutorService announcer;

    public PgNotifyRunnerRegistry(DataSource dataSource, ObjectMapper objectMapper,
            @Value("${app.runner.relay.self-address:}") String selfHost,
            @Value("${app.runner.relay.port:8081}") int relayPort,
            @Value("${app.runner.presence.announce-ms:15000}") long announceMs,
            @Value("${app.runner.presence.stale-after-ms:45000}") long staleAfterMs) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.selfAddress = selfHost == null || selfHost.isBlank()
                ? ""
                : "http://" + selfHost.trim() + ":" + relayPort;
        this.announceMs = announceMs > 0 ? announceMs : 15_000L;
        this.staleAfterMs = staleAfterMs > 0 ? staleAfterMs : 45_000L;
    }

    @PostConstruct
    void start() {
        running.set(true);
        listener = new Thread(this::listenLoop, "runner-presence-listener");
        listener.setDaemon(true);
        listener.start();
        announcer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "runner-presence-announcer");
            thread.setDaemon(true);
            return thread;
        });
        announcer.scheduleWithFixedDelay(this::announceAndExpire, announceMs, announceMs,
                TimeUnit.MILLISECONDS);
        // Demande de resynchronisation : les pods déjà en place ré-émettent leurs CONNECT locaux, ce
        // qui fait converger notre carte distante en une poignée de millisecondes plutôt qu'à la
        // prochaine (dé)connexion de runner.
        notifyEvent(EVENT_SYNC_REQUEST, null);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (announcer != null) {
            announcer.shutdownNow();
        }
        if (listener != null) {
            listener.interrupt();
        }
    }

    @Override
    public void register(RunnerConnection connection) {
        local.put(connection.workspaceId(), connection);
        notifyEvent(EVENT_CONNECT, connection.workspaceId());
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
            notifyEvent(EVENT_DISCONNECT, workspaceId);
        }
    }

    @Override
    public Optional<RunnerConnection> findLocal(UUID workspaceId) {
        return Optional.ofNullable(local.get(workspaceId));
    }

    @Override
    public Optional<RemoteRunnerNode> findRemote(UUID workspaceId) {
        RemotePresence presence = freshRemote(workspaceId);
        if (presence == null || presence.address().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new RemoteRunnerNode(presence.nodeId(), presence.address()));
    }

    @Override
    public boolean isConnected(UUID workspaceId) {
        return local.containsKey(workspaceId) || freshRemote(workspaceId) != null;
    }

    /**
     * Présence distante <b>non périmée</b>, ou {@code null}. Une entrée trop ancienne est retirée à
     * la lecture : un pod disparu sans DISCONNECT (OOMKilled, évincé) ne doit pas laisser croire
     * indéfiniment qu'un runner est joignable.
     */
    private RemotePresence freshRemote(UUID workspaceId) {
        RemotePresence presence = remote.get(workspaceId);
        if (presence == null) {
            return null;
        }
        if (isStale(presence)) {
            remote.remove(workspaceId, presence);
            return null;
        }
        return presence;
    }

    private boolean isStale(RemotePresence presence) {
        return presence.seenAt().isBefore(Instant.now().minusMillis(staleAfterMs));
    }

    /** Ré-annonce des connexions locales + purge des présences distantes périmées. */
    private void announceAndExpire() {
        try {
            remote.forEach((workspaceId, presence) -> {
                if (isStale(presence)) {
                    remote.remove(workspaceId, presence);
                }
            });
            local.keySet().forEach(workspaceId -> notifyEvent(EVENT_CONNECT, workspaceId));
        } catch (RuntimeException ex) {
            // Le planificateur ne doit jamais s'arrêter sur une erreur ponctuelle.
            log.warn("Ré-annonce de présence runner en échec : {}", ex.getMessage());
        }
    }

    /**
     * Diffuse un événement de présence aux autres replicas. {@code workspaceId} est {@code null} pour
     * un {@code SYNC_REQUEST}, qui ne vise aucun workspace en particulier.
     */
    private void notifyEvent(String event, UUID workspaceId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("event", event);
        if (workspaceId != null) {
            payload.put("workspaceId", workspaceId.toString());
        }
        payload.put("nodeId", nodeId);
        payload.put("address", selfAddress);
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
            String event = node.path("event").asText();
            if (EVENT_SYNC_REQUEST.equals(event)) {
                // Un pod vient de démarrer : on lui rend nos connexions locales. On ne rediffuse
                // JAMAIS le SYNC_REQUEST lui-même — sinon deux pods s'en renverraient sans fin.
                local.keySet().forEach(workspaceId -> notifyEvent(EVENT_CONNECT, workspaceId));
                return;
            }
            UUID workspaceId = UUID.fromString(node.path("workspaceId").asText());
            if (EVENT_CONNECT.equals(event)) {
                remote.put(workspaceId,
                        new RemotePresence(emitter, node.path("address").asText(""), Instant.now()));
            } else if (EVENT_DISCONNECT.equals(event)) {
                remote.computeIfPresent(workspaceId,
                        (ws, current) -> current.nodeId().equals(emitter) ? null : current);
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

    /**
     * Présence annoncée par un autre nœud : qui l'annonce, où le joindre, et quand on l'a vue pour la
     * dernière fois. L'horodatage est ce qui permet de périmer un pod parti sans se signaler.
     */
    private record RemotePresence(String nodeId, String address, Instant seenAt) {
    }
}
