package fr.claudegateway.runner.relay;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;

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

    /**
     * Garde-fou de durée d'un flux de tour relayé (F-84 / SF-84-02). Aligné sur la durée de vie
     * maximale d'un flux SSE de terminal : un relais ne doit jamais survivre au tour qu'il regarde.
     */
    private static final long TURN_RELAY_MAX_MS = 900_000L;

    private final RelayInterruptTarget interruptTarget;
    private final RelaySessionInterruptTarget sessionInterruptTarget;
    private final LiveTurnRegistry liveTurns;
    private final RunnerRelayProperties properties;
    private final ObjectMapper objectMapper;

    public AtelierRelayController(RelayInterruptTarget interruptTarget,
            RelaySessionInterruptTarget sessionInterruptTarget, LiveTurnRegistry liveTurns,
            RunnerRelayProperties properties, ObjectMapper objectMapper) {
        this.interruptTarget = interruptTarget;
        this.sessionInterruptTarget = sessionInterruptTarget;
        this.liveTurns = liveTurns;
        this.properties = properties;
        this.objectMapper = objectMapper;
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

    /**
     * Dépose une précision sur ce pod (F-39 / SF-39-19). <b>Toujours 200</b>, comme la confirmation :
     * « ce n'est pas moi qui exécutais » est le cas de tous les pods sauf un, et ce n'est pas une
     * erreur.
     */
    @PostMapping(value = "/steer", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> steer(
            @RequestBody(required = false) RelayGestureRequests.SteerRequest request) {
        if (request == null || !request.isValid()) {
            return ResponseEntity.badRequest().build();
        }
        interruptTarget.steerLocally(request.userId(), request.workspaceId(), request.message());
        log.debug("Précision relayée déposée (workspace={})", request.workspaceId());
        return ResponseEntity.ok(Map.of("accepted", true));
    }


    /**
     * « Détiens-tu ce tour ? » (F-84 / SF-84-02) — la sonde diffusée à tous les pairs.
     *
     * <p>Réponse <b>toujours 200</b> : {@code owner=false} veut dire « ce n'est pas moi qui
     * exécute », ce qui est le cas de tous les pods sauf un, et n'est pas une erreur de transport.
     * Le pod propriétaire rend son <b>adresse</b> ({@code http://{POD_IP}:8081}), jamais dérivée
     * d'un identifiant : c'est lui qui la connaît.</p>
     *
     * <p>Le {@code userId} de l'enveloppe n'authentifie rien — l'authentification est le secret
     * partagé. Il est rejoué comme <b>critère</b> : le registre étant clef par
     * {@code (userId, workspaceId)}, un tour d'autrui reste introuvable.</p>
     */
    @PostMapping(value = "/turn-owner", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> turnOwner(
            @RequestBody(required = false) RelayGestureRequests.TurnRequest request) {
        if (request == null || !request.isValid()) {
            return ResponseEntity.badRequest().build();
        }
        Optional<LiveTurn> turn = liveTurns.find(request.userId(), request.workspaceId());
        if (turn.isEmpty()) {
            return ResponseEntity.ok(Map.of("owner", false));
        }
        return ResponseEntity.ok(Map.of(
                "owner", true,
                "baseUrl", properties.selfBaseUrl(),
                "turnId", turn.get().turnId().toString(),
                "cursor", turn.get().cursor(),
                "startedAt", turn.get().startedAtMs()));
    }

    /**
     * Le <b>flux</b> du tour que ce pod exécute, rendu en NDJSON à un pod pair (F-84 / SF-84-02) :
     * le rejeu depuis le curseur, puis le direct, puis la ligne de fin.
     *
     * <p>Le pair recopie chaque ligne dans le flux SSE de son navigateur. Les numéros d'ordre sont
     * ceux d'<b>ici</b> : un spectateur relayé et un spectateur local reçoivent la même suite.</p>
     *
     * <p>Un pair qui s'en va détache son spectateur ; le tour, lui, continue — c'est la règle de
     * SF-84-01, appliquée au relais.</p>
     */
    @PostMapping(value = "/turn-stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseEntity<StreamingResponseBody> turnStream(
            @RequestBody(required = false) RelayGestureRequests.TurnRequest request) {
        if (request == null || !request.isValid()) {
            return ResponseEntity.badRequest().build();
        }
        Optional<LiveTurn> found = liveTurns.find(request.userId(), request.workspaceId());
        StreamingResponseBody body = output -> {
            NdjsonTurnSubscriber subscriber = new NdjsonTurnSubscriber(output, objectMapper);
            if (found.isEmpty()) {
                subscriber.writeAttached(false, null, 0L, 0L);
                subscriber.finish();
                return;
            }
            LiveTurn turn = found.get();
            if (!subscriber.writeAttached(true, turn.turnId().toString(), turn.cursor(),
                    turn.startedAtMs())) {
                return;
            }
            if (!turn.attach(subscriber, request.safeCursor())) {
                // Le tour vient de se terminer, ou le pair est parti pendant le rejeu.
                subscriber.finish();
                return;
            }
            try {
                subscriber.awaitFinish(turn, TURN_RELAY_MAX_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                turn.detach(subscriber);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_NDJSON)
                .body(body);
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
