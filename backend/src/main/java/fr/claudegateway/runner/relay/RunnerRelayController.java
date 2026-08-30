package fr.claudegateway.runner.relay;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.channel.RunnerCallResult;

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

    private final RunnerCallDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public RunnerRelayController(RunnerCallDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
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
            @RequestHeader(value = RunnerRelayAuthFilter.ORIGIN_HEADER, required = false) String origin) {

        if (request == null || !request.isValid()) {
            return ResponseEntity.badRequest().build();
        }
        log.debug("Relais entrant (origine={}, workspace={}, appel={}, outil={})", origin,
                request.workspaceId(), request.callId(), request.tool());

        StreamingResponseBody body = output -> {
            NdjsonSink sink = new NdjsonSink(output);
            RunnerCallResult result = dispatcher.call(request.workspaceId(), request.callId(),
                    request.tool(), request.input(), request.timeoutMs(), sink::writeChunk);
            sink.writeResult(result);
            log.debug("Relais servi (workspace={}, appel={}, ok={}, code={})", request.workspaceId(),
                    request.callId(), result.ok(), result.errorCode());
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_NDJSON)
                .body(body);
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
