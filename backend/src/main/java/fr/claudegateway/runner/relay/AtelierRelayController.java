package fr.claudegateway.runner.relay;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestes d'<b>interruption</b> reçus d'un pod pair (F-38 / SF-38-13, contrat du relais §6).
 *
 * <p>Deux marques, deux clefs, deux routes — assumé : un tour d'atelier est identifié par
 * {@code userId:workspaceId}, une session Managed Agent par son identifiant fournisseur. Les fusionner
 * mélangerait deux durées de vie et deux émetteurs.</p>
 *
 * <p>Ces routes n'accèdent à <b>aucune donnée persistée</b> : elles ne touchent que des marques en
 * mémoire et des appels en vol. L'appartenance du workspace a déjà été vérifiée
 * ({@code requireOwned}) sur le pod qui a reçu la requête de l'utilisateur ; ici, le
 * {@code userId} n'est qu'une clef de marque, jamais une authentification — celle-ci est le secret
 * partagé, vérifié en amont par {@link RunnerRelayAuthFilter}.</p>
 *
 * <p>Une marque posée sur un pod qui n'exécutait rien est sans effet : la boucle l'efface à
 * l'ouverture de chaque tour.</p>
 */
@RestController
@RequestMapping("/internal/atelier")
@Conditional(RunnerRelayEnabledCondition.class)
public class AtelierRelayController {

    private static final Logger log = LoggerFactory.getLogger(AtelierRelayController.class);

    private final RelayInterruptTarget interruptTarget;
    private final RelaySessionInterruptTarget sessionInterruptTarget;

    public AtelierRelayController(RelayInterruptTarget interruptTarget,
            RelaySessionInterruptTarget sessionInterruptTarget) {
        this.interruptTarget = interruptTarget;
        this.sessionInterruptTarget = sessionInterruptTarget;
    }

    /**
     * Applique une interruption de tour sur ce pod : marque, libération de la porte, annulation des
     * appels en vol — dans cet ordre, celui de {@code AtelierChatService.interruptChat}.
     */
    @PostMapping(value = "/interrupt", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> interrupt(
            @RequestBody(required = false) RelayGestureRequests.InterruptRequest request) {
        if (request == null || !request.isValid()) {
            return ResponseEntity.badRequest().build();
        }
        RelayInterruptTarget.RelayInterruptOutcome outcome = interruptTarget
                .interruptLocally(request.userId(), request.workspaceId(), request.safeReason());
        log.debug("Interruption relayée appliquée (workspace={}, libérées={}, annulées={})",
                request.workspaceId(), outcome.released(), outcome.cancelled());
        return ResponseEntity.ok(Map.of(
                "marked", true,
                "released", outcome.released(),
                "cancelled", outcome.cancelled()));
    }

    /** Pose ou retire la marque d'interruption d'une session Managed Agent (F-32). */
    @PostMapping(value = "/session-interrupt", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> sessionInterrupt(
            @RequestBody(required = false) RelayGestureRequests.SessionInterruptRequest request) {
        if (request == null || !request.isValid()) {
            return ResponseEntity.badRequest().build();
        }
        sessionInterruptTarget.markSessionInterruptedLocally(request.sessionId(), request.mark());
        return ResponseEntity.ok(Map.of("marked", request.mark()));
    }
}
