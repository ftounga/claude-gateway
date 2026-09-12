package fr.claudegateway.runner.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.live.TurnEvent;

/**
 * Enveloppe NDJSON du <b>flux de tour</b> relayé entre pods (F-84 / SF-84-02) : une ligne = un objet
 * JSON compact suivi d'un {@code \n}, comme {@link RelayNdjson} le fait pour un appel d'outil.
 *
 * <p>Quatre types, et un ordre imposé : exactement une ligne {@code attached} (qui dit si ce pod
 * détient bien le tour), puis zéro à N lignes {@code event} entrecoupées de {@code ping}, puis
 * exactement une ligne {@code end}, toujours la dernière.</p>
 *
 * <p>Le {@code ping} n'a aucun sens fonctionnel : il empêche le délai de lecture du pair de couper
 * un spectateur pendant qu'une commande longue tourne en silence. Un lecteur l'ignore.</p>
 */
final class TurnRelayNdjson {

    static final String TYPE_ATTACHED = "attached";
    static final String TYPE_EVENT = "event";
    static final String TYPE_PING = "ping";
    static final String TYPE_END = "end";

    private TurnRelayNdjson() {
    }

    /** Première ligne : ce pod détient le tour (ou non). */
    static String attachedLine(ObjectMapper mapper, boolean attached, String turnId, long cursor,
            long startedAtMs) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", TYPE_ATTACHED);
        node.put("attached", attached);
        if (turnId == null) {
            node.putNull("turnId");
        } else {
            node.put("turnId", turnId);
        }
        node.put("cursor", cursor);
        node.put("startedAt", startedAtMs);
        return node.toString();
    }

    /** Un événement du tour, numéro compris — c'est lui qui garantit « la même suite » aux deux vues. */
    static String eventLine(ObjectMapper mapper, TurnEvent event) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", TYPE_EVENT);
        node.put("seq", event.seq());
        node.put("name", event.name());
        node.put("json", event.json());
        return node.toString();
    }

    static String pingLine(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", TYPE_PING);
        return node.toString();
    }

    static String endLine(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", TYPE_END);
        return node.toString();
    }

    /** Reconstruit un événement depuis sa ligne. Aucun champ n'est deviné. */
    static TurnEvent toEvent(JsonNode node) {
        return new TurnEvent(node.path("seq").asLong(0L), node.path("name").asText(""),
                node.path("json").asText(""));
    }
}
