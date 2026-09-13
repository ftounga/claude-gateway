package fr.claudegateway.radar.sync;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.relay.RunnerCallRouter;

/**
 * <b>Les appels du Radar au runner du poste</b> (F-100). Un seul point de passage : la cible est
 * <b>le poste du périmètre</b> — jamais un identifiant venu d'une requête — sans projet (même forme que
 * la carte du poste, F-92), et chaque appel est journalisé dans l'audit runner.
 */
@Component
public class RadarRunnerCalls {

    /** La vérification guidée : la sonde observe quelques secondes (délai des outils Teams, F-87). */
    public static final String VERIFY = "teams_radar_verify";
    public static final long VERIFY_TIMEOUT_MS = 20_000L;

    private static final Logger log = LoggerFactory.getLogger(RadarRunnerCalls.class);

    private final RunnerCallRouter router;
    private final RunnerAuditService audit;

    public RadarRunnerCalls(RunnerCallRouter router, RunnerAuditService audit) {
        this.router = router;
        this.audit = audit;
    }

    /** Appelle un outil {@code teams_radar_*} sur le poste du périmètre. */
    public RunnerCallResult call(RadarScope scope, String tool, JsonNode input, long timeoutMs) {
        RunnerTarget target = new RunnerTarget(scope.hostId(), null, "");
        String callId = UUID.randomUUID().toString();
        RunnerCallResult result = router.call(target, callId, tool, input, timeoutMs);
        try {
            audit.recordCall(scope.userId(), target, callId, tool, "radar", result);
        } catch (RuntimeException e) {
            // Le journal ne doit pas faire échouer la synchro : on le dit dans les traces, sans contenu.
            log.warn("Audit d'un appel Radar non écrit (poste={}, outil={})", scope.hostId(), tool);
        }
        return result;
    }
}
