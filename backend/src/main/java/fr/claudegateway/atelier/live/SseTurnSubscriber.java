package fr.claudegateway.atelier.live;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Un flux SSE vu comme ce qu'il est depuis F-84 : <b>un spectateur parmi d'autres</b>
 * (SF-84-01).
 *
 * <p>La charge utile part telle qu'elle a été sérialisée au moment de la publication — mêmes octets
 * pour tout le monde, au direct comme au rejeu.</p>
 */
public final class SseTurnSubscriber implements TurnSubscriber {

    /**
     * Le JSON part <b>déjà sérialisé</b> : ce sont donc des octets, pas une chaîne à reconvertir.
     *
     * <p>Et il faut que ce soit des octets. Le convertisseur de chaînes de Spring lit le jeu de
     * caractères dans les <b>en-têtes de la réponse</b> — ici {@code text/event-stream}, sans
     * charset — et retombe alors sur ISO-8859-1 : « Terminé » arrivait mutilé à l'écran. En
     * écrivant nous-mêmes en UTF-8, les octets sont exactement ceux que Jackson produisait avant
     * F-84, pour tous les spectateurs et à l'identique.</p>
     */
    private static final MediaType RAW_BYTES = MediaType.APPLICATION_OCTET_STREAM;

    private final SseEmitter emitter;

    public SseTurnSubscriber(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public boolean deliver(TurnEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.name())
                    .data(event.json().getBytes(StandardCharsets.UTF_8), RAW_BYTES));
            return true;
        } catch (IOException | RuntimeException ex) {
            // Le navigateur est parti (route changée, onglet fermé, délai de flux atteint). Ce
            // n'est pas une panne : c'est le cas nominal que F-84 devait cesser de confondre avec
            // un ordre d'arrêt. Rien n'est journalisé — cela arriverait à chaque navigation.
            return false;
        }
    }

    @Override
    public void finish() {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // Flux déjà clos : rien à faire de plus.
        }
    }
}
