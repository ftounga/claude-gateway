package fr.claudegateway.runner.relay;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.exec.NoPendingConfirmationException;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Point d'entrée du relais interne, côté <b>pod propriétaire de la socket</b>
 * (F-38 / SF-38-12, contrat du relais §3).
 *
 * <p><b>Règle structurelle anti-boucle</b> : ce contrôleur appelle {@link RunnerCallDispatcher}
 * <b>directement</b>, jamais {@link RunnerCallRouter}. Un second saut n'est donc pas exprimable dans
 * le code — un seul saut, garanti par la structure et non par un compteur. Si la socket n'est pas
 * (ou plus) ici, le dispatcher rend {@code runner_unavailable} dans la ligne {@code result} : le
 * mauvais pod ne fait rien exécuter chez le mauvais runner.</p>
 *
 * <p>Le contrôleur n'existe que si le relais est configuré, et son chemin n'est servi que sur le port
 * du connecteur interne (voir {@link RunnerRelayAuthFilter}).</p>
 */
@RestController
@RequestMapping("/internal/runner")
@Conditional(RunnerRelayEnabledCondition.class)
public class RunnerRelayController {

    private static final Logger log = LoggerFactory.getLogger(RunnerRelayController.class);

    /**
     * Marge ajoutée au {@code timeoutMs} de l'appel pour obtenir le délai async HTTP de cet
     * endpoint (SF-38-28) : grâce dispatcher (5 s, {@link RunnerCallDispatcher#DEFAULT_GRACE_MS})
     * + 10 s de sécurité. Le dispatcher rend son {@code runner_timeout} à {@code timeoutMs + grâce} ;
     * la coupe async, à {@code timeoutMs + 15 s}, arrive donc toujours <b>après</b> l'issue de
     * l'appel — jamais avant. Même raisonnement que {@code RunnerRelayProperties.readTimeoutMs}
     * (côté appelant), transposé par requête au côté récepteur.
     */
    private static final long ASYNC_TIMEOUT_MARGIN_MS = 15_000L;

    private final RunnerCallDispatcher dispatcher;
    private final RunnerConfirmationGate confirmationGate;
    private final ObjectMapper objectMapper;

    public RunnerRelayController(RunnerCallDispatcher dispatcher,
            RunnerConfirmationGate confirmationGate, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.confirmationGate = confirmationGate;
        this.objectMapper = objectMapper;
    }

    /**
     * Exécute un appel d'outil sur la socket locale et rend son déroulé en NDJSON : les fragments de
     * flux au fil de l'eau, puis l'issue.
     *
     * <p>Rien n'est accumulé ici : chaque {@code tool_stream} devient une ligne écrite et
     * <i>flushée</i> dès sa réception. Les deux écritures possibles — un fragment sur le thread de
     * réception WebSocket, la ligne terminale sur le thread de requête — sont sérialisées par un
     * verrou porté par la réponse. Sans ce verrou, une ligne NDJSON pourrait être coupée en deux et
     * le cadrage du flux serait perdu.</p>
     */
    @PostMapping(value = "/call", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseEntity<StreamingResponseBody> call(@RequestBody RelayCallRequest request,
            @RequestHeader(value = RunnerRelayAuthFilter.ORIGIN_HEADER, required = false) String origin,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {

        if (request == null || !request.isValid()) {
            return ResponseEntity.badRequest().build();
        }
        log.debug("Relais entrant (origine={}, poste={}, appel={}, outil={})", origin,
                request.hostId(), request.callId(), request.tool());

        // Aligne le délai async HTTP de CETTE requête sur le délai propre de l'appel d'outil
        // (SF-38-28) : sans cela, le StreamingResponseBody retombait sur le défaut court du
        // conteneur et un gros tour était tué par une AsyncRequestTimeoutException avant que le
        // dispatcher ait pu rendre son issue.
        applyAsyncTimeout(httpRequest, httpResponse, request.timeoutMs());

        StreamingResponseBody body = output -> {
            NdjsonSink sink = new NdjsonSink(output);
            try {
                RunnerCallResult result = dispatcher.call(request.target(), request.callId(),
                        request.tool(), request.input(), request.timeoutMs(), sink::writeChunk);
                sink.writeResult(result);
                log.debug("Relais servi (poste={}, appel={}, ok={}, code={})", request.hostId(),
                        request.callId(), result.ok(), result.errorCode());
            } catch (IOException | RuntimeException ex) {
                // Le flux porte déjà application/x-ndjson : laisser l'exception remonter à
                // l'@ExceptionHandler ferait écrire un ErrorResponse OBJET dessus
                // (HttpMessageNotWritableException, défaut tracé le 2026-09-18). On rend plutôt une
                // ligne `result` terminale d'erreur, best-effort, et l'on se tait si même cela
                // échoue (client déjà parti). Le dispatcher, lui, ne lève pas : il rend
                // `runner_timeout`/`runner_unavailable` en ligne `result` — ce filet ne se
                // déclenche que sur l'imprévu (écriture finale sur un client parti, sérialisation).
                log.warn("Appel relayé interrompu (poste={}, appel={}) : {}", request.hostId(),
                        request.callId(), ex.toString());
                sink.writeResultQuietly(RunnerCallResult.backendError(
                        RunnerErrorCodes.RUNNER_PROTOCOL_ERROR,
                        "L'appel relayé n'a pas pu être finalisé."));
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_NDJSON)
                .body(body);
    }

    /**
     * Fixe le délai async de cette requête à {@code timeoutMs + marge} (SF-38-28), par
     * remplacement de l'{@link AsyncWebRequest} du {@link WebAsyncManager} avant que le traitement
     * concurrent ne démarre. Voie <b>per-endpoint</b> : contrairement à
     * {@code spring.mvc.async.request-timeout} (global), elle ne relève pas le plafond des autres
     * endpoints async, et le chat SSE — qui porte son propre délai via {@code SseEmitter} — n'est
     * pas concerné.
     */
    private static void applyAsyncTimeout(HttpServletRequest httpRequest,
            HttpServletResponse httpResponse, long toolTimeoutMs) {
        WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(httpRequest);
        if (asyncManager.isConcurrentHandlingStarted()) {
            return; // Traitement async déjà en cours : on ne reconfigure rien.
        }
        AsyncWebRequest asyncWebRequest =
                WebAsyncUtils.createAsyncWebRequest(httpRequest, httpResponse);
        asyncWebRequest.setTimeout(toolTimeoutMs + ASYNC_TIMEOUT_MARGIN_MS);
        asyncManager.setAsyncWebRequest(asyncWebRequest);
    }

    /**
     * Annule les appels d'outils en vol d'un workspace sur <b>ce</b> pod (contrat du relais §4).
     *
     * <p>Route diffusée : un pod qui n'héberge pas la socket rend simplement {@code 0}, ce n'est pas
     * une erreur. L'annulation <b>ne ferme jamais</b> le flux NDJSON d'un appel en cours — le runner
     * tue son processus et émet quand même sa trame terminale (contrat de messages §2.5), donc la
     * ligne {@code result} part normalement. Couper la réponse ici serait une régression.</p>
     */
    @PostMapping(value = "/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> cancel(
            @RequestBody(required = false) RelayGestureRequests.CancelRequest request) {
        if (request == null || !request.isValid()) {
            return ResponseEntity.badRequest().build();
        }
        int cancelled = dispatcher.cancelWorkspace(request.workspaceId(), request.safeReason());
        return ResponseEntity.ok(Map.of("cancelled", cancelled));
    }

    /**
     * Remet une trame de commande au runner d'un poste si son canal vit sur <b>ce</b> pod
     * (F-111 / SF-111-04). Toujours 200 : {@code delivered=false} veut dire « pas chez moi ».
     */
    @PostMapping(value = "/control", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> control(
            @RequestBody(required = false) RelayGestureRequests.ControlRequest request) {
        if (request == null || !request.isValid()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(Map.of("delivered", dispatcher.sendControl(request.hostId(), request.frame())));
    }

    /**
     * Tranche une demande d'autorisation qui attendrait sur <b>ce</b> pod (contrat du relais §5).
     *
     * <p>C'est le geste que le multi-pod cassait le plus silencieusement : la porte
     * ({@link RunnerConfirmationGate}) vit sur le pod qui exécute la boucle, alors que la requête du
     * navigateur peut atterrir sur n'importe lequel. Sans cette route, toute commande aurait fini
     * refusée au bout de 120 s par expiration.</p>
     *
     * <p>Réponse <b>toujours 200</b> : {@code resolved=false} veut dire « ce n'est pas moi qui
     * attendais », ce qui est le cas de tous les pods sauf un — l'absence de demande n'est pas une
     * erreur de transport. L'appartenance est revérifiée par la porte elle-même ({@code userId}
     * <i>et</i> {@code workspaceId}) : le {@code userId} de l'enveloppe n'authentifie rien, il est
     * rejoué comme critère.</p>
     */
    @PostMapping(value = "/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> confirm(
            @RequestBody(required = false) RelayGestureRequests.ConfirmRequest request) {
        if (request == null || !request.isValid()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            confirmationGate.resolve(request.userId(), request.workspaceId(), request.callId().trim(),
                    request.allow(), request.reason());
            log.debug("Décision d'autorisation relayée et appliquée (workspace={}, appel={})",
                    request.workspaceId(), request.callId());
            return ResponseEntity.ok(Map.of("resolved", true));
        } catch (NoPendingConfirmationException ex) {
            return ResponseEntity.ok(Map.of("resolved", false));
        }
    }

    /**
     * Écriture sérialisée des lignes NDJSON d'une réponse. Le verrou est propre à la requête : deux
     * appels relayés concurrents n'ont aucune raison de s'attendre l'un l'autre.
     */
    private final class NdjsonSink {

        private final OutputStream output;
        private final Object lock = new Object();

        private NdjsonSink(OutputStream output) {
            this.output = output;
        }

        /**
         * Relais d'un fragment. Appelé sur le thread de réception WebSocket : une écriture impossible
         * (client parti) est remontée en {@link UncheckedIOException}, ce que le dispatcher traite en
         * débranchant le relais — l'appel se poursuit et son résultat part normalement.
         */
        private void writeChunk(String chunk) {
            write(RelayNdjson.streamLine(objectMapper, chunk));
        }

        private void writeResult(RunnerCallResult result) throws IOException {
            try {
                write(RelayNdjson.resultLine(objectMapper, result));
            } catch (UncheckedIOException ex) {
                throw ex.getCause();
            }
        }

        /**
         * Écrit une ligne {@code result} terminale sans jamais lever : le dernier recours du chemin
         * d'erreur (SF-38-28). Si l'écriture échoue à son tour (client déjà parti), il n'y a plus
         * rien à faire — l'appel est terminé côté modèle — et surtout rien ne doit remonter vers
         * l'@ExceptionHandler, qui tenterait un ErrorResponse objet sur ce flux ndjson.
         */
        private void writeResultQuietly(RunnerCallResult result) {
            try {
                write(RelayNdjson.resultLine(objectMapper, result));
            } catch (UncheckedIOException ex) {
                log.debug("Ligne d'erreur terminale non écrite (flux déjà clos)");
            }
        }

        private void write(String line) {
            synchronized (lock) {
                try {
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                    output.write('\n');
                    output.flush();
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        }
    }
}
