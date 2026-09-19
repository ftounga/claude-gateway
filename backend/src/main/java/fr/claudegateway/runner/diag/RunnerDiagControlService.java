package fr.claudegateway.runner.diag;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.runner.relay.RunnerRelayBroadcaster;

/**
 * Règle le <b>niveau de diagnostic d'un poste</b> le temps d'un diagnostic (F-132 / SF-132-05).
 *
 * <p>Envoie au runner une trame de contrôle {@code runner_diag_level} sur le canal descendant
 * existant — d'abord le canal local ({@link RunnerCallDispatcher#sendControl}), sinon la diffusion
 * cross-pod ({@link RunnerRelayBroadcaster#broadcastControl}), exactement comme la mise à jour du
 * runner. Le retour à {@code INFO} est <b>automatique</b> côté runner à l'expiration : aucune
 * persistance, aucune commande de retour à envoyer.</p>
 *
 * <p><b>Isolation</b> : {@link RunnerHostService#requireOwned} avant tout envoi (404 sinon) ;
 * l'identité vient du JWT, jamais d'un paramètre.</p>
 */
@Service
public class RunnerDiagControlService {

    /** Le seul niveau temporaire proposé : le but est un diagnostic ponctuel. */
    public static final String DEBUG_LEVEL = "DEBUG";
    /** Durée par défaut du DEBUG si l'appelant n'en donne pas. */
    public static final int DEFAULT_MINUTES = 10;
    /** Durée maximale : un diagnostic est ponctuel, pas un régime permanent. */
    public static final int MAX_MINUTES = 60;

    private static final Logger log = LoggerFactory.getLogger(RunnerDiagControlService.class);

    private final RunnerHostService hostService;
    private final RunnerCallDispatcher dispatcher;
    private final RunnerRelayBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    public RunnerDiagControlService(RunnerHostService hostService, RunnerCallDispatcher dispatcher,
            RunnerRelayBroadcaster broadcaster, ObjectMapper objectMapper) {
        this.hostService = hostService;
        this.dispatcher = dispatcher;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
    }

    /**
     * Passe un poste possédé en {@code DEBUG} pour {@code minutes} minutes (bornées). Rend le nombre
     * de minutes effectif et si la commande a été <b>remise</b> au runner ({@code false} si le poste
     * n'est joignable sur aucun pod — l'appelant l'affiche, ce n'est pas une panne).
     */
    public Outcome enableDebug(UUID userId, UUID hostId, Integer minutes) {
        hostService.requireOwned(userId, hostId); // 404 si non possédé — isolation d'abord
        int effective = clampMinutes(minutes);
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("type", "runner_diag_level");
        frame.put("level", DEBUG_LEVEL);
        frame.put("ttlSeconds", (long) effective * 60L);
        String payload = frame.toString();
        boolean delivered = dispatcher.sendControl(hostId, payload)
                || broadcaster.broadcastControl(hostId, payload);
        log.info("Niveau de diagnostic DEBUG demandé (poste={}, minutes={}, remis={})", hostId,
                effective, delivered);
        return new Outcome(delivered, DEBUG_LEVEL, effective);
    }

    private static int clampMinutes(Integer minutes) {
        if (minutes == null) {
            return DEFAULT_MINUTES;
        }
        return Math.max(1, Math.min(MAX_MINUTES, minutes));
    }

    /** L'issue d'une demande de réglage : remis ? niveau, minutes effectives. */
    public record Outcome(boolean delivered, String level, int minutes) {
    }
}
