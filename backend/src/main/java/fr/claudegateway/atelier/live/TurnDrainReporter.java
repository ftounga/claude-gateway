package fr.claudegateway.atelier.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Ce que l'arrêt du pod emporte avec lui (F-84 / SF-84-08).
 *
 * <p>Le 2026-09-16, un déploiement a tué un tour en cours chez un client. Rien ne l'a dit : côté
 * serveur le processus s'est arrêté, côté écran le flux SSE s'est seulement détaché et la page
 * affichait encore « en réflexion ». Le drainage introduit par SF-84-08 rend cette perte rare ;
 * il ne la rend pas impossible — un tour plus long que le délai reste perdu. <b>Un drainage
 * invisible ne se diagnostique pas</b> : cette classe existe pour qu'une ligne de journal, datée,
 * dise ce qu'il restait à drainer au moment du {@code SIGTERM}.</p>
 *
 * <p>Elle n'attend rien et ne bloque rien : l'attente, elle, est faite par le pool
 * {@code chatStreamExecutor} (voir {@code ChatStreamConfig}), dont le {@code stop()} de cycle de
 * vie s'exécute <b>après</b> cet événement. Le compte annoncé ici est donc bien celui d'avant le
 * drainage — la mesure utile.</p>
 *
 * <p><b>Ce qui n'est pas journalisé</b> : ni identifiant d'utilisateur, ni identifiant de projet,
 * ni contenu de message. Un compte, et c'est tout.</p>
 */
@Component
public class TurnDrainReporter {

    private static final Logger log = LoggerFactory.getLogger(TurnDrainReporter.class);

    private final LiveTurnRegistry registry;
    private final int drainSeconds;

    public TurnDrainReporter(
            LiveTurnRegistry registry,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.shutdown.turn-drain-seconds:300}") int drainSeconds) {
        this.registry = registry;
        this.drainSeconds = drainSeconds;
    }

    /** Annonce, au début de l'arrêt, le nombre de tours encore vivants sur ce pod. */
    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        int live = registry.liveCount();
        if (live == 0) {
            log.info("Arrêt du pod : aucun tour vivant, rien à drainer");
            return;
        }
        // WARN et pas INFO : c'est le seul moment où un tour peut encore être perdu, et c'est la
        // ligne qu'on ira chercher le jour où un client dira « il s'est arrêté tout seul ».
        log.warn("Arrêt du pod : {} tour(s) encore vivant(s), drainage borné à {} s — "
                + "au-delà, le ou les tours restants sont perdus", live, drainSeconds);
    }
}
