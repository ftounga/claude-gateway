package fr.claudegateway.runner.channel;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.RunnerHeartbeatService;
import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerLiveness;

/**
 * Gestionnaire du canal WebSocket runner (F-38 / SF-38-02, étendu en SF-38-05). À l'établissement il
 * enregistre la connexion dans le {@link RunnerRegistry} et marque le runner vu
 * ({@code last_seen_at}). Chaque heartbeat ({@code {"type":"heartbeat"}} ou trame pong) rafraîchit
 * {@code last_seen_at} et reçoit un {@code heartbeat_ack}. À la fermeture, la connexion est retirée
 * du registre.
 *
 * <p>Depuis SF-38-05, toute trame <b>autre</b> que le heartbeat est aiguillée vers le
 * {@link RunnerCallDispatcher} avec l'identité issue de la <b>session</b> (jamais un identifiant lu
 * dans le message). Un type inconnu reste ignoré en silence : c'est ce qui permet à un runner
 * antérieur de cohabiter avec un backend plus récent (contrat de messages §0).</p>
 */
@Component
public class RunnerWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RunnerWebSocketHandler.class);

    /** Clé sous laquelle la présence enregistrée par CETTE session est retenue (garde anti-course). */
    static final String CONNECTION_ATTRIBUTE = "runnerConnection";

    /**
     * Fermeture d'une socket muette : code {@code SESSION_NOT_RELIABLE} (4500), avec un motif qui dit
     * la vraie raison — le motif par défaut de ce code parle d'un envoi trop lent, et le runner
     * l'affiche tel quel dans sa console. ASCII, bien sous les 123 octets du protocole.
     */
    static final CloseStatus SILENT_SOCKET =
            CloseStatus.SESSION_NOT_RELIABLE.withReason("no heartbeat received in time");

    private final RunnerRegistry registry;
    private final RunnerHeartbeatService heartbeatService;
    private final ObjectMapper objectMapper;
    private final RunnerCallDispatcher dispatcher;
    private final RunnerLiveness liveness;
    private final String nodeId = UUID.randomUUID().toString();
    /** Sockets ouvertes sur CE nœud, que le balayage examine (F-97 / SF-97-01). */
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    /**
     * Le journal des ruptures (F-161 / SF-161-03), branché par mutateur : sans lui, ces sockets se
     * ferment exactement comme avant.
     */
    private fr.claudegateway.runner.rupture.RunnerDisconnectJournal journal;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJournal(fr.claudegateway.runner.rupture.RunnerDisconnectJournal journal) {
        this.journal = journal;
    }

    /** L'instant d'ouverture de CETTE socket, pour dire combien de temps elle a tenu. */
    private static final String OPENED_AT_ATTRIBUTE = "runnerSocketOpenedAt";

    public RunnerWebSocketHandler(RunnerRegistry registry, RunnerHeartbeatService heartbeatService,
            ObjectMapper objectMapper, RunnerCallDispatcher dispatcher, RunnerLiveness liveness) {
        this.registry = registry;
        this.heartbeatService = heartbeatService;
        this.objectMapper = objectMapper;
        this.dispatcher = dispatcher;
        this.liveness = liveness;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        RunnerIdentity identity = identityOf(session);
        // La session décorée est posée AVANT l'enregistrement : dès que la présence est visible, une
        // socket utilisable l'est aussi.
        dispatcher.attach(session, identity);
        RunnerConnection connection = new RunnerConnection(
                identity.hostId(), identity.userId(), identity.tokenId(), nodeId,
                OffsetDateTime.now());
        // Retenue sur la session : c'est elle, et elle seule, que la fermeture pourra retirer.
        session.getAttributes().put(CONNECTION_ATTRIBUTE, connection);
        // F-161 / SF-161-03 : sans cet instant, on saurait qu'une socket est tombée mais pas si
        // elle a tenu deux secondes ou six heures — or c'est cette durée qui oriente l'enquête.
        session.getAttributes().put(OPENED_AT_ATTRIBUTE, java.time.Instant.now());
        registry.register(connection);
        heartbeatService.touch(identity.tokenId());
        // Suivie par le balayage APRÈS le premier battement : une socket neuve n'est jamais muette.
        sessions.add(session);
        log.debug("Runner connecte: poste={} token={}", identity.hostId(), identity.tokenId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        RunnerIdentity identity = identityOf(session);
        JsonNode frame = parse(message.getPayload());
        if (frame == null) {
            log.debug("Trame runner illisible ignoree (poste={})", identity.hostId());
            return;
        }
        String type = frame.path("type").asText(null);
        if ("heartbeat".equals(type)) {
            heartbeatService.touch(identity.tokenId());
            // Même instance d'écriture que les tool_call : la session Spring n'est pas thread-safe.
            dispatcher.outboundFor(session).sendMessage(new TextMessage("{\"type\":\"heartbeat_ack\"}"));
            return;
        }
        dispatcher.onFrame(identity, type, frame);
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        heartbeatService.touch(identityOf(session).tokenId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        RunnerIdentity identity = identityOf(session);
        // Consigné AVANT `release` : celui-ci termine les appels en vol, et après il n'y aurait
        // plus rien à compter. Le CloseStatus finissait jusqu'ici dans le log.debug ci-dessous,
        // c'est-à-dire nulle part en production — alors qu'il sépare une coupure réseau d'un arrêt
        // applicatif (F-161 / SF-161-03).
        journal(session, identity,
                fr.claudegateway.runner.rupture.RunnerDisconnectCause.SOCKET_FERMEE,
                status == null ? null : status.toString());
        release(session, identity);
        log.debug("Runner deconnecte: poste={} token={} ({})",
                identity.hostId(), identity.tokenId(), status);
    }

    /**
     * Ferme les sockets dont le poste <b>ne bat plus</b> (F-97 / SF-97-01).
     *
     * <p>Un runner qui meurt sans fermer sa connexion — ordinateur en veille, Wi-Fi coupé, VPN tombé —
     * ne prévient personne : {@link #afterConnectionClosed} n'est jamais appelé, et la socket à moitié
     * ouverte restait enregistrée jusqu'à ce que l'ingress la coupe (15 min). Le long-polling avait son
     * balayage ; le WebSocket n'en avait pas. Même cadence, même règle que
     * {@link RunnerPollingSessions#sweepIdleChannels} : muet depuis plus de {@code stale-after} →
     * fermé ({@code SESSION_NOT_RELIABLE}), puis libéré par le <b>même chemin</b> qu'une fermeture
     * ordinaire, garde anti-course comprise.</p>
     *
     * <p><b>Le doute ne coupe pas</b> : si la fraîcheur ne peut pas être lue (base indisponible), la
     * socket est laissée ouverte.</p>
     */
    @Scheduled(fixedDelayString = "${app.runner.websocket.sweep-ms:15000}")
    void sweepSilentSockets() {
        Map<UUID, Boolean> aliveByHost = new HashMap<>();
        for (WebSocketSession session : List.copyOf(sessions)) {
            RunnerIdentity identity = (RunnerIdentity) session.getAttributes()
                    .get(RunnerHandshakeInterceptor.IDENTITY_ATTRIBUTE);
            if (identity == null) {
                continue;
            }
            Boolean alive = aliveByHost.computeIfAbsent(identity.hostId(), host -> {
                try {
                    return liveness.isAlive(identity.userId(), host);
                } catch (RuntimeException ex) {
                    log.warn("Fraîcheur du battement illisible (poste={}) : socket conservée", host);
                    return Boolean.TRUE;
                }
            });
            if (Boolean.TRUE.equals(alive)) {
                continue;
            }
            log.info("Socket runner muette fermée (poste={}) : aucun battement depuis plus de la "
                    + "fenêtre de fraîcheur", identity.hostId());
            try {
                dispatcher.outboundFor(session).close(SILENT_SOCKET);
            } catch (IOException | RuntimeException ex) {
                log.debug("Fermeture d'une socket runner muette en échec (poste={})",
                        identity.hostId());
            } finally {
                journal(session, identity,
                        fr.claudegateway.runner.rupture.RunnerDisconnectCause.SOCKET_MUETTE,
                        SILENT_SOCKET.toString());
                // La fermeture d'une socket à moitié ouverte ne rappelle pas toujours
                // afterConnectionClosed : on libère nous-mêmes. Idempotent si elle l'a fait.
                release(session, identity);
            }
        }
    }

    /**
     * Libère une session fermée : appels en vol terminés, <b>puis</b> présence retirée — aucun appel
     * n'attend une socket morte, et aucun n'est rejoué (un write_file rejoué serait destructeur).
     *
     * <p>Garde anti-course (F-97 / SF-97-01, même principe que le long-polling) : la présence n'est
     * retirée que si celle enregistrée est encore <b>exactement</b> celle de cette session. La garde
     * par jeton du registre ne suffit pas : un runner qui se reconnecte garde le même jeton, et la
     * fermeture tardive de sa vieille socket effaçait sa nouvelle présence. Idempotent.</p>
     */
    /**
     * Consigne la rupture de cette socket. Best-effort de bout en bout : ni l'absence de journal,
     * ni l'absence d'identité ne doivent empêcher une socket de se fermer.
     */
    private void journal(WebSocketSession session, RunnerIdentity identity,
            fr.claudegateway.runner.rupture.RunnerDisconnectCause cause, String closeStatus) {
        if (journal == null || identity == null) {
            return;
        }
        Object openedAt = session.getAttributes().get(OPENED_AT_ATTRIBUTE);
        journal.record(identity.userId(), identity.hostId(), cause,
                fr.claudegateway.runner.rupture.RunnerTransport.WEBSOCKET,
                openedAt instanceof java.time.Instant instant ? instant : null,
                null, closeStatus, dispatcher.inFlightCountFor(identity.hostId()));
    }

    private void release(WebSocketSession session, RunnerIdentity identity) {
        sessions.remove(session);
        dispatcher.detach(session, identity);
        Object registered = session.getAttributes().get(CONNECTION_ATTRIBUTE);
        if (registered instanceof RunnerConnection mine) {
            if (registry.findLocal(identity.hostId()).filter(mine::equals).isPresent()) {
                registry.unregister(identity.hostId(), identity.tokenId());
            }
            return;
        }
        registry.unregister(identity.hostId(), identity.tokenId());
    }

    private RunnerIdentity identityOf(WebSocketSession session) {
        RunnerIdentity identity =
                (RunnerIdentity) session.getAttributes().get(RunnerHandshakeInterceptor.IDENTITY_ATTRIBUTE);
        if (identity == null) {
            // Ne devrait jamais arriver : le handshake interceptor rejette toute session sans jeton.
            throw new IllegalStateException("Session runner sans identite : handshake non authentifie");
        }
        return identity;
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            return null; // Charge utile illisible : ignorée, la socket n'est jamais fermée pour ça.
        }
    }
}
