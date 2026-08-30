package fr.claudegateway.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Aiguillage d'une trame entrante du contrat de messages (F-38 / SF-38-09), <b>indépendant du
 * transport</b> : la même trame doit produire le même effet qu'elle soit arrivée par le WebSocket ou
 * par le repli long-polling. Sans ce point unique, les deux chemins dériveraient l'un de l'autre.
 *
 * <p>Règle de compatibilité ascendante (contrat §0) : un {@code type} inconnu est <b>ignoré en
 * silence</b>. Jamais une erreur, jamais une fermeture de canal — c'est ce qui permet à un runner
 * ancien de cohabiter avec une gateway plus récente, et l'inverse.</p>
 */
public final class FrameRouter {

    private final ToolDispatcher dispatcher;
    private final Console console;
    private final ObjectMapper mapper = new ObjectMapper();

    public FrameRouter(ToolDispatcher dispatcher, Console console) {
        this.dispatcher = dispatcher;
        this.console = console;
    }

    /** Analyse puis aiguille une trame reçue. Ne lève jamais. */
    public void route(String payload) {
        JsonNode frame;
        try {
            frame = mapper.readTree(payload);
        } catch (Exception e) {
            dispatcher.sendProtocolError("unparsable", "Trame illisible.", null);
            return;
        }
        if (frame == null || !frame.isObject()) {
            dispatcher.sendProtocolError("unparsable", "Trame illisible.", null);
            return;
        }
        String type = frame.path("type").asText(null);
        if (type == null) {
            dispatcher.sendProtocolError("invalid_envelope", "Champ type manquant.", null);
            return;
        }
        switch (type) {
            case "heartbeat_ack" -> console.info("Heartbeat confirmé (ack).");
            case "tool_call" -> dispatcher.onToolCall(frame);
            case "tool_cancel" -> dispatcher.onToolCancel(frame);
            default -> {
                // Type inconnu : ignoré, jamais une erreur ni une fermeture de canal.
            }
        }
    }
}
