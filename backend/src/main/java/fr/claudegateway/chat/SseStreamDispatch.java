package fr.claudegateway.chat;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Soumet le relais d'un flux SSE à l'exécuteur dédié, et <b>dit</b> le refus quand il n'y a plus de
 * place (F-70 / SF-70-01).
 *
 * <p>Depuis que le pool remet les tâches directement (file de capacité 0, voir
 * {@link ChatStreamConfig}), une soumission au-delà du plafond lève
 * {@link RejectedExecutionException} au lieu d'attendre en file. C'est le comportement voulu : une
 * attente muette donne un écran qui affiche « en cours » devant un agent qui dort. Mais un refus qui
 * remonterait en exception sur un endpoint {@code text/event-stream} produirait un 406 illisible —
 * il est donc émis <b>dans le flux</b>, comme toutes les erreurs de pré-vol, puis le flux se ferme
 * proprement.</p>
 */
public final class SseStreamDispatch {

    private static final Logger log = LoggerFactory.getLogger(SseStreamDispatch.class);

    /** Code d'erreur reçu par l'écran quand aucun thread de flux n'est disponible. */
    public static final String BUSY = "stream_busy";

    private SseStreamDispatch() {
    }

    /**
     * Lance {@code relay} sur {@code executor} ; en cas de refus, envoie {@code error: stream_busy}
     * puis complète l'émetteur.
     */
    public static void submit(Executor executor, SseEmitter emitter, Runnable relay) {
        try {
            executor.execute(relay);
        } catch (RejectedExecutionException ex) {
            // Jamais le message de l'exception vers le client : il décrit l'état du pool.
            log.warn("Flux refusé : aucun thread de streaming disponible");
            try {
                emitter.send(SseEmitter.event().name("error").data(new BusyError(BUSY)));
                emitter.complete();
            } catch (IOException | RuntimeException sendFailure) {
                emitter.completeWithError(sendFailure);
            }
        }
    }

    /** Même forme que les autres événements d'erreur : {@code {"error": "..."}}. */
    public record BusyError(String error) {
    }
}
