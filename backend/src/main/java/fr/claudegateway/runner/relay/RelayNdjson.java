package fr.claudegateway.runner.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.channel.RunnerCallResult;

/**
 * Enveloppe NDJSON du relais (F-38 / SF-38-12, contrat du relais §3) : une ligne = un objet JSON
 * compact suivi d'un {@code \n}.
 *
 * <p>Deux types seulement, et un ordre imposé : {@code stream} zéro à N fois, puis exactement une
 * {@code result}, toujours la dernière. Rien après le {@code result}. Le miroir de
 * {@link RunnerCallResult} est champ pour champ — y compris les {@code null} explicites — pour que
 * l'appelant reconstruise l'issue sans rien inventer.</p>
 */
final class RelayNdjson {

    static final String TYPE_STREAM = "stream";
    static final String TYPE_RESULT = "result";

    private RelayNdjson() {
    }

    /** Ligne de flux : un fragment de sortie tel que le runner l'a émis. */
    static String streamLine(ObjectMapper mapper, String chunk) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", TYPE_STREAM);
        node.put("chunk", chunk);
        return node.toString();
    }

    /** Ligne terminale : miroir champ pour champ de {@link RunnerCallResult}. */
    static String resultLine(ObjectMapper mapper, RunnerCallResult result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", TYPE_RESULT);
        node.put("ok", result.ok());
        node.put("content", result.content());
        node.put("truncated", result.truncated());
        if (result.exitCode() == null) {
            node.putNull("exitCode");
        } else {
            node.put("exitCode", result.exitCode());
        }
        node.put("durationMs", result.durationMs());
        if (result.bytes() == null) {
            node.putNull("bytes");
        } else {
            node.put("bytes", result.bytes());
        }
        if (result.errorCode() == null) {
            node.putNull("errorCode");
        } else {
            node.put("errorCode", result.errorCode());
        }
        if (result.errorMessage() == null) {
            node.putNull("errorMessage");
        } else {
            node.put("errorMessage", result.errorMessage());
        }
        node.put("streamed", result.streamed());
        node.put("streamTruncated", result.streamTruncated());
        return node.toString();
    }

    /**
     * Reconstruit l'issue depuis la ligne {@code result}. Le {@code streamed} vient <b>exclusivement
     * d'ici</b> : les fragments déjà relayés à l'appelant ne sont jamais ré-agrégés, sinon la sortie
     * serait affichée et comptée deux fois.
     */
    static RunnerCallResult toResult(JsonNode node) {
        return new RunnerCallResult(
                node.path("ok").asBoolean(false),
                node.path("content").asText(""),
                node.path("truncated").asBoolean(false),
                node.path("exitCode").isNumber() ? node.path("exitCode").asInt() : null,
                Math.max(0L, node.path("durationMs").asLong(0L)),
                node.path("bytes").isNumber() ? node.path("bytes").asLong() : null,
                node.path("errorCode").isTextual() ? node.path("errorCode").asText() : null,
                node.path("errorMessage").isTextual() ? node.path("errorMessage").asText() : null,
                node.path("streamed").asText(""),
                node.path("streamTruncated").asBoolean(false));
    }
}
