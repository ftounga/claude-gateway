package fr.claudegateway.atelier.live;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * <b>Le battement de cœur des tours vivants</b> (F-170 / SF-170-01).
 *
 * <h2>Pourquoi</h2>
 *
 * <p>Le PO travaille derrière un proxy d'entreprise (CAGIP) qui <b>coupe une connexion streaming
 * restée inactive</b>. Le tour « vit dans le flux » (F-84) : pendant que le modèle réfléchit ou qu'un
 * outil long tourne, <b>aucun octet ne circule</b> sur le flux SSE du tour — le proxy le prend pour
 * une connexion morte et la coupe, et le tour est tronqué côté écran.</p>
 *
 * <p>Ce composant fait circuler, à intervalle régulier, un <b>commentaire SSE</b> ({@code : ping}) sur
 * chaque flux de tour vivant. Le commentaire est ignoré nativement par les clients SSE ; il ne sert
 * qu'à garder la connexion vivante. Il n'entre pas au tampon du tour, ne consomme aucun numéro
 * d'ordre, n'est pas persisté et reste invisible.</p>
 *
 * <h2>Un seul planificateur, un balayage par tic</h2>
 *
 * <p>Un unique thread démon bat à l'intervalle configuré et parcourt {@link LiveTurnRegistry#liveTurns()}
 * — les tours vivants de ce pod. Chaque {@link LiveTurn#heartbeat()} pousse le signal à ses propres
 * spectateurs (sous le verrou du tour) et détache ceux qui sont partis. Le battement s'arrête de
 * lui-même pour un tour terminé : il quitte la liste des tours vivants. Aucun couplage au chemin de
 * requête — ni au contrôleur, ni au constructeur du registre.</p>
 *
 * <h2>Réglage</h2>
 *
 * <p>Intervalle en millisecondes via {@code app.atelier.stream.heartbeat} (env
 * {@code APP_ATELIER_STREAM_HEARTBEAT}), défaut 15 000 ms — bien <b>sous</b> le seuil de coupure
 * d'inactivité des proxys usuels. Une valeur {@code <= 0} <b>désactive</b> le battement (comportement
 * d'avant F-170), sans erreur.</p>
 */
@Component
public class TurnHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(TurnHeartbeat.class);

    private final LiveTurnRegistry registry;
    private final long intervalMs;
    private final ScheduledExecutorService scheduler;

    public TurnHeartbeat(LiveTurnRegistry registry,
            @Value("${app.atelier.stream.heartbeat:15000}") long intervalMs) {
        this.registry = registry;
        this.intervalMs = intervalMs;
        this.scheduler = newScheduler();
    }

    /**
     * Arme le battement au démarrage, sauf s'il est désactivé ({@code intervalMs <= 0}).
     *
     * <p>{@code scheduleWithFixedDelay} (et non {@code atFixedRate}) : si un tic traîne, le suivant
     * attend sans rafale — un battement n'a aucune raison de se rattraper.</p>
     */
    @PostConstruct
    void start() {
        if (intervalMs <= 0) {
            log.info("Battement de tour désactivé (app.atelier.stream.heartbeat <= 0)");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::beat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Battement de tour armé : un signal toutes les {} ms", intervalMs);
    }

    /**
     * Un tic : pingue chaque tour vivant du pod. Robuste — une exception lors d'un tic ne doit jamais
     * tuer le planificateur (sinon plus aucun tour ne serait tenu en vie).
     */
    void beat() {
        try {
            for (LiveTurn turn : registry.liveTurns()) {
                turn.heartbeat();
            }
        } catch (RuntimeException ex) {
            log.debug("Battement de tour ignoré ({})", ex.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    private static ScheduledExecutorService newScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = Executors.defaultThreadFactory().newThread(runnable);
            thread.setName("turn-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
