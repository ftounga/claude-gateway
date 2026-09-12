package fr.claudegateway.runner.relay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.live.RemoteTurnSource;
import fr.claudegateway.atelier.live.TurnSubscriber;

/**
 * Le tour d'un pod voisin, lu depuis <b>ce</b> pod (F-84 / SF-84-02).
 *
 * <p>Le raisonnement est celui d'ADR-016, déjà éprouvé pour l'autorisation et l'interruption : aucun
 * annuaire ne dit où tourne une boucle — le navigateur et le runner sont deux clients équilibrés
 * séparément. On <b>sonde</b> donc les pairs (« qui détient ce tour ? »), puis on <b>dirige</b> le
 * flux vers celui qui répond, à son adresse {@code http://{POD_IP}:8081}.</p>
 *
 * <p><b>Toujours présent, souvent muet</b> — comme {@link RunnerRelayBroadcaster}. Sans secret de
 * relais (dev, tests, mono-pod), ce composant ne fait rigoureusement rien et rend « aucun tour
 * ailleurs » : l'appelant dégrade alors vers l'état d'origine, jamais vers un comportement
 * inventé.</p>
 *
 * <p>Journalisation : jamais le secret, jamais une charge utile d'événement.</p>
 */
@Component
public class RelayTurnSource implements RemoteTurnSource {

    static final String OWNER_PATH = "/api/internal/atelier/turn-owner";
    static final String STREAM_PATH = "/api/internal/atelier/turn-stream";

    private static final Logger log = LoggerFactory.getLogger(RelayTurnSource.class);

    private final RunnerRelayProperties properties;
    private final RelayPeerResolver peerResolver;
    private final RelayPeerClient peerClient;
    private final ObjectMapper objectMapper;
    private final RestClient streamClient;

    public RelayTurnSource(RunnerRelayProperties properties, RelayPeerResolver peerResolver,
            RelayPeerClient peerClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.peerResolver = peerResolver;
        this.peerClient = peerClient;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        // Délai ENTRE DEUX OCTETS, pas délai total : le pod propriétaire bat toutes les 20 s, un
        // tour qui réfléchit longtemps n'est donc jamais coupé, un pair muet l'est.
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.streamClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Optional<RemoteTurnState> findRemoteTurn(UUID userId, UUID workspaceId) {
        return findOwner(userId, workspaceId).map(Owner::state);
    }

    @Override
    public boolean streamRemoteTurn(UUID userId, UUID workspaceId, long cursor,
            TurnSubscriber subscriber) {
        Optional<Owner> owner = findOwner(userId, workspaceId);
        if (owner.isEmpty()) {
            return false;
        }
        URI uri = URI.create(owner.get().baseUrl() + STREAM_PATH);
        try {
            return Boolean.TRUE.equals(streamClient.post()
                    .uri(uri)
                    .header(RunnerRelayAuthFilter.SECRET_HEADER, properties.getSecret())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_NDJSON)
                    .body(turnPayload(userId, workspaceId, cursor))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200) {
                            log.debug("Pair a refusé le relais de tour (statut={})",
                                    response.getStatusCode().value());
                            return Boolean.FALSE;
                        }
                        return read(response.getBody(), subscriber);
                    }));
        } catch (RuntimeException ex) {
            // Pair injoignable ou flux coupé : on dégrade vers l'état d'origine — « rien de vivant
            // ici ». Le spectateur pourra réessayer ; rien n'est inventé entre-temps.
            log.debug("Relais de tour injoignable : {}", ex.getClass().getSimpleName());
            return false;
        }
    }

    // ------------------------------------------------------------------ interne

    /** Sonde les pairs : le premier qui dit détenir le tour l'emporte, avec son adresse. */
    private Optional<Owner> findOwner(UUID userId, UUID workspaceId) {
        if (!properties.isEnabled() || userId == null || workspaceId == null) {
            return Optional.empty();
        }
        List<String> peers;
        try {
            peers = peerResolver.peerBaseUrls();
        } catch (RuntimeException ex) {
            log.debug("Résolution des pairs impossible pour un tour : aucun relais");
            return Optional.empty();
        }
        String body = turnPayload(userId, workspaceId, 0L);
        for (String peer : peers) {
            Optional<JsonNode> answer = peerClient.post(peer, OWNER_PATH, body);
            if (answer.isEmpty() || !answer.get().path("owner").asBoolean(false)) {
                continue;
            }
            JsonNode node = answer.get();
            String baseUrl = node.path("baseUrl").asText("");
            // Une adresse vide (présence non convergée) ou la nôtre : relayer n'aboutirait à rien.
            // Même garde que RunnerCallRouter — on ne s'appelle jamais soi-même.
            if (baseUrl.isBlank() || baseUrl.equals(properties.selfBaseUrl())) {
                continue;
            }
            return Optional.of(new Owner(baseUrl, new RemoteTurnState(
                    parseUuid(node.path("turnId").asText(null)),
                    node.path("cursor").asLong(0L),
                    node.path("startedAt").asLong(0L))));
        }
        return Optional.empty();
    }

    /** Lit le NDJSON du pair et recopie chaque événement dans le spectateur, ligne à ligne. */
    private Boolean read(java.io.InputStream stream, TurnSubscriber subscriber) {
        boolean attached = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (IOException ex) {
                    log.debug("Ligne de relais de tour illisible : flux abandonné");
                    return attached;
                }
                String type = node.path("type").asText("");
                if (TurnRelayNdjson.TYPE_ATTACHED.equals(type)) {
                    attached = node.path("attached").asBoolean(false);
                    if (!attached) {
                        return Boolean.FALSE;
                    }
                } else if (TurnRelayNdjson.TYPE_EVENT.equals(type)) {
                    if (!subscriber.deliver(TurnRelayNdjson.toEvent(node))) {
                        // Le navigateur est parti : on abandonne le relais, pas le tour.
                        return attached;
                    }
                } else if (TurnRelayNdjson.TYPE_END.equals(type)) {
                    return attached;
                }
                // Tout autre type (ping compris, et ceux d'une version plus récente) est ignoré.
            }
        } catch (IOException ex) {
            log.debug("Flux de relais de tour coupé avant la fin");
        }
        return attached;
    }

    private String turnPayload(UUID userId, UUID workspaceId, long cursor) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("userId", userId.toString());
        node.put("workspaceId", workspaceId.toString());
        node.put("cursor", cursor);
        return node.toString();
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Un pod pair qui détient le tour : son adresse, et l'état qu'il en donne. */
    private record Owner(String baseUrl, RemoteTurnState state) {
    }

    /**
     * Instance <b>inerte</b>, pour les tests qui montent un contrôleur à la main : aucun pair, aucun
     * appel réseau, exactement le comportement mono-pod.
     */
    public static RelayTurnSource disabled() {
        RunnerRelayProperties properties = new RunnerRelayProperties();
        ObjectMapper mapper = new ObjectMapper();
        return new RelayTurnSource(properties, new RelayPeerResolver(properties),
                new RelayPeerClient(properties, mapper), mapper);
    }
}
