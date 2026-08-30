package fr.claudegateway.runner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.channel.LongPollingRunnerOutbound;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.channel.RunnerPollingSessions;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * <b>Repli de transport</b> du runner (F-38 / SF-38-09) : quand un proxy d'entreprise tue le
 * WebSocket, le runner bascule sur ces trois endpoints HTTP. Ils transportent <b>exactement les
 * mêmes enveloppes</b> que le canal WS — aucun type de message nouveau (contrat §2) — dans un corps
 * JSON au lieu d'une trame texte.
 *
 * <ul>
 *   <li>{@code POST /runner/poll} — le runner réclame les trames sortantes en attente (long-poll) ;</li>
 *   <li>{@code POST /runner/send} — le runner dépose ses trames entrantes (une, ou un lot) ;</li>
 *   <li>{@code POST /runner/disconnect} — arrêt propre ({@code Ctrl-C} côté runner).</li>
 * </ul>
 *
 * <p><b>Authentification (décision D9)</b> : aucun filtre HTTP ne connaît le jeton runner —
 * {@code RunnerHandshakeInterceptor} ne couvre que le handshake WebSocket. Ce contrôleur authentifie
 * donc lui-même l'en-tête {@code X-Runner-Token} via {@link RunnerTokenAuthenticator} et refuse en
 * <b>401 générique</b>. Il ne pose <b>jamais</b> d'{@code AuthenticatedUser} dans le
 * {@code SecurityContext} : un jeton runner n'est pas un JWT utilisateur et ne doit jamais pouvoir
 * atteindre un endpoint utilisateur. La chaîne dédiée {@code /runner/**} ({@link RunnerSecurityConfig})
 * laisse passer ces trois URL en {@code permitAll} ; tout le reste y est refusé.</p>
 *
 * <p><b>Isolation</b> : l'identité ({@code workspaceId}, {@code userId}) vient exclusivement du jeton
 * présenté, jamais d'un champ du message. Une trame postée avec le jeton du projet A ne peut donc
 * pas terminer un appel du projet B (garde déjà tenue par {@link RunnerCallDispatcher}).</p>
 */
@RestController
@RequestMapping("/runner")
public class RunnerPollController {

    /** En-tête portant le jeton runner. Volontairement pas {@code Authorization} : voir la note. */
    public static final String TOKEN_HEADER = "X-Runner-Token";

    private static final Logger log = LoggerFactory.getLogger(RunnerPollController.class);

    /** Trames acceptées dans un même {@code POST /runner/send} (contrat §5, borne défensive). */
    private static final int MAX_FRAMES_PER_SEND = 64;

    private static final String HEARTBEAT_ACK = "{\"type\":\"heartbeat_ack\"}";

    private final RunnerTokenAuthenticator authenticator;
    private final RunnerPollingSessions sessions;
    private final RunnerCallDispatcher dispatcher;
    private final RunnerHeartbeatService heartbeatService;
    private final ObjectMapper objectMapper;
    private final long maxWaitMs;

    public RunnerPollController(RunnerTokenAuthenticator authenticator, RunnerPollingSessions sessions,
            RunnerCallDispatcher dispatcher, RunnerHeartbeatService heartbeatService,
            ObjectMapper objectMapper,
            @Value("${app.runner.poll.max-wait-ms:25000}") long maxWaitMs) {
        this.authenticator = authenticator;
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.heartbeatService = heartbeatService;
        this.objectMapper = objectMapper;
        this.maxWaitMs = maxWaitMs > 0 ? maxWaitMs : 25_000L;
    }

    /**
     * Long-poll : rend les trames sortantes en attente, en bloquant au plus {@code waitMs}. Une
     * réponse vide au bout du délai est le fonctionnement <b>normal</b>, pas une erreur — le runner
     * repolle aussitôt.
     *
     * <p>Le poll <i>est</i> le heartbeat de ce transport : il rafraîchit {@code last_seen_at}, si
     * bien que le runner n'a pas de minuteur séparé à tenir.</p>
     */
    @PostMapping(value = "/poll", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> poll(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestParam(value = "waitMs", required = false) Long waitMs) throws InterruptedException {
        Optional<RunnerIdentity> identity = authenticator.authenticate(trim(token));
        if (identity.isEmpty()) {
            return unauthorized();
        }
        LongPollingRunnerOutbound channel = sessions.open(identity.get());
        heartbeatService.touch(identity.get().tokenId());

        List<String> frames = channel.drain(Duration.ofMillis(clampWait(waitMs)));
        if (frames.isEmpty() && !channel.isOpen()) {
            // Le canal a été coupé pendant l'attente (coupe-circuit, révocation, balayage) : le
            // runner doit s'arrêter, pas repoller indéfiniment.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json(new ErrorResponse("runner_channel_closed",
                            "La liaison runner a été fermée par la gateway.")));
        }
        return ResponseEntity.ok(framesBody(frames));
    }

    /**
     * Dépôt des trames entrantes du runner ({@code ready}, {@code tool_stream}, {@code tool_result},
     * {@code protocol_error}, {@code heartbeat}). Le corps accepte un lot
     * {@code {"frames":[…]}} ou une trame nue.
     *
     * <p>Rien n'y est jamais une erreur bloquante : une trame illisible, d'un type inconnu, ou
     * rattachée à un appel disparu est ignorée en silence (contrat §0 et §7). Fermer le canal sur une
     * trame inattendue casserait la compatibilité entre un runner ancien et un backend récent.</p>
     */
    @PostMapping(value = "/send", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> send(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) String body) {
        Optional<RunnerIdentity> identity = authenticator.authenticate(trim(token));
        if (identity.isEmpty()) {
            return unauthorizedEntity();
        }
        heartbeatService.touch(identity.get().tokenId());
        for (JsonNode frame : parseFrames(body)) {
            handleIncoming(identity.get(), frame);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Arrêt propre côté runner. Ferme <b>son</b> canal : les appels en vol sont terminés en
     * {@code runner_unavailable} et la présence est retirée (avec la garde anti-course par
     * {@code tokenId} — la fin d'un polling ne doit jamais effacer la connexion d'un runner qui
     * vient de se reconnecter).
     */
    @PostMapping("/disconnect")
    public ResponseEntity<?> disconnect(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        Optional<RunnerIdentity> identity = authenticator.authenticate(trim(token));
        if (identity.isEmpty()) {
            return unauthorizedEntity();
        }
        sessions.close(identity.get());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ outils

    private void handleIncoming(RunnerIdentity identity, JsonNode frame) {
        String type = frame.path("type").asText(null);
        if ("heartbeat".equals(type)) {
            // Compatibilité : un runner qui garde son minuteur reçoit bien son ack, mis en file
            // comme n'importe quelle trame sortante (le poll suivant le livre).
            sessions.find(identity).ifPresent(channel -> {
                try {
                    channel.send(HEARTBEAT_ACK);
                } catch (java.io.IOException ex) {
                    log.debug("heartbeat_ack non mis en file : canal indisponible");
                }
            });
            return;
        }
        dispatcher.onFrame(identity, type, frame);
    }

    /**
     * Trames portées par le corps : {@code {"frames":[…]}} ou une trame nue. Tout ce qui n'est pas un
     * objet JSON est écarté sans bruit — le runner ne doit pas pouvoir provoquer une 4xx en postant
     * une trame que ce backend ne comprend pas.
     */
    private List<JsonNode> parseFrames(String body) {
        List<JsonNode> frames = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return frames;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception ex) {
            log.debug("Corps /runner/send illisible : ignoré");
            return frames;
        }
        if (root == null) {
            return frames;
        }
        if (root.isObject() && root.path("frames").isArray()) {
            for (JsonNode frame : root.path("frames")) {
                if (frame.isObject() && frames.size() < MAX_FRAMES_PER_SEND) {
                    frames.add(frame);
                }
            }
        } else if (root.isObject()) {
            frames.add(root);
        }
        return frames;
    }

    /** Corps de réponse du poll : les trames <b>verbatim</b>, réassemblées sans être réécrites. */
    private static String framesBody(List<String> frames) {
        return "{\"frames\":[" + String.join(",", frames) + "]}";
    }

    private long clampWait(Long requested) {
        if (requested == null) {
            return maxWaitMs;
        }
        return Math.max(0L, Math.min(requested, maxWaitMs));
    }

    private static String trim(String token) {
        return token == null ? null : token.trim();
    }

    /**
     * Refus <b>générique</b> : aucune distinction entre en-tête absent, jeton inconnu, expiré ou
     * révoqué — un message différencié serait un oracle pour qui teste des jetons.
     */
    private ResponseEntity<String> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json(new ErrorResponse("runner_unauthorized", "Jeton runner invalide.")));
    }

    private ResponseEntity<?> unauthorizedEntity() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("runner_unauthorized", "Jeton runner invalide."));
    }

    private String json(ErrorResponse error) {
        try {
            return objectMapper.writeValueAsString(error);
        } catch (Exception ex) {
            return "{\"error\":\"runner_unauthorized\"}";
        }
    }
}
