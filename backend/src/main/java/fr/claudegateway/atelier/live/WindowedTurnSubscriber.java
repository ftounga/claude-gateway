package fr.claudegateway.atelier.live;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Un spectateur <b>par fenêtre</b> (F-84 / SF-84-04) : il suit le tour comme un autre, mais sa
 * réponse se <b>clôt vite</b>.
 *
 * <h2>Pourquoi</h2>
 *
 * <p>Constat de production du 2026-09-13 : derrière un proxy d'entreprise qui inspecte le TLS
 * (Netskope), le corps d'une réponse {@code text/event-stream} est <b>retenu jusqu'à sa fin</b>. Le
 * tour a exécuté 29 appels d'outils en 492 s, et l'écran n'en a rien reçu avant la dernière seconde.
 * Aucun en-tête ni rembourrage ne contourne ce comportement ; une réponse <b>complète</b>, elle, est
 * relâchée. Ce spectateur livre donc ce qu'il a, puis clôt — et l'écran se rebranche aussitôt avec
 * son curseur (SF-84-02) : même endpoint, mêmes événements, même numérotage.</p>
 *
 * <h2>Quand la fenêtre se clôt</h2>
 *
 * <ul>
 *   <li>{@value #LINGER_MS} ms après le premier <b>événement de tour</b> livré (numéro &gt; 0), qu'il
 *       vienne du rejeu ou du direct : le court délai regroupe une rafale (une commande et sa
 *       sortie) dans la même réponse ;</li>
 *   <li>au plus tard à l'échéance demandée, bornée à [{@value #MIN_WAIT_MS} ;
 *       {@value #MAX_WAIT_MS}] ms ;</li>
 *   <li>à la fin du tour, comme tout spectateur.</li>
 * </ul>
 *
 * <p>Les <b>apartés</b> ({@code attached}, {@code confirm_state}…, numéro 0) ne déclenchent rien :
 * ils ne sont pas du neuf, et clore sur eux ferait tourner l'écran en boucle pendant une attente
 * d'autorisation.</p>
 *
 * <p>Clore une fenêtre <b>détache</b> le spectateur et ne touche jamais au tour — la règle de
 * SF-84-01.</p>
 */
public final class WindowedTurnSubscriber implements TurnSubscriber {

    /** Délai entre le premier événement livré et la clôture. */
    public static final long LINGER_MS = 250L;

    /** Échéance minimale d'une fenêtre. */
    public static final long MIN_WAIT_MS = 1_000L;

    /** Échéance maximale : sous les délais d'inactivité usuels des proxys et de l'ingress. */
    public static final long MAX_WAIT_MS = 25_000L;

    /** Programme une action différée ; rend de quoi l'annuler. Remplaçable en test. */
    @FunctionalInterface
    public interface Timer {
        Runnable schedule(Runnable action, long delayMs);
    }

    private static final ScheduledExecutorService SHARED = sharedScheduler();

    private final TurnSubscriber delegate;
    private final LiveTurn turn;
    private final Timer timer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean lingering = new AtomicBoolean(false);
    private volatile Runnable cancelDeadline = () -> { };
    private volatile Runnable cancelLinger = () -> { };

    private WindowedTurnSubscriber(TurnSubscriber delegate, LiveTurn turn, Timer timer) {
        this.delegate = delegate;
        this.turn = turn;
        this.timer = timer;
    }

    /**
     * Ouvre une fenêtre sur {@code turn} et arme son échéance. Le spectateur n'est <b>pas</b> encore
     * branché : c'est {@link LiveTurn#attach} qui le fait, rejeu compris.
     */
    public static WindowedTurnSubscriber open(TurnSubscriber delegate, LiveTurn turn, Timer timer,
            long waitMs) {
        WindowedTurnSubscriber window = new WindowedTurnSubscriber(delegate, turn, timer);
        window.cancelDeadline = timer.schedule(window::close, boundedWait(waitMs));
        return window;
    }

    /** L'échéance demandée, ramenée dans ses bornes. */
    public static long boundedWait(long waitMs) {
        return Math.max(MIN_WAIT_MS, Math.min(MAX_WAIT_MS, waitMs));
    }

    /** Le minuteur de production : un seul thread démon, partagé, sans rien de bloquant dessus. */
    public static Timer sharedTimer() {
        return (action, delayMs) -> {
            ScheduledFuture<?> future = SHARED.schedule(action, delayMs, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        };
    }

    @Override
    public boolean deliver(TurnEvent event) {
        if (closed.get()) {
            return false;
        }
        if (!delegate.deliver(event)) {
            // Navigateur parti : c'est le tour qui retire ce spectateur en lisant `false`. Le
            // détacher d'ici modifierait sa liste pendant qu'il la parcourt.
            close(false);
            return false;
        }
        if (event.seq() > 0 && lingering.compareAndSet(false, true)) {
            cancelLinger = timer.schedule(this::close, LINGER_MS);
        }
        return true;
    }

    @Override
    public void finish() {
        close(true);
    }

    /** Clôture programmée (rafale livrée ou échéance) : jamais appelée sous le verrou du tour. */
    private void close() {
        close(true);
    }

    /** Clôt la fenêtre une seule fois : annule les minuteurs, détache si demandé, termine la réponse. */
    private void close(boolean detach) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelDeadline.run();
        cancelLinger.run();
        if (detach && turn != null) {
            turn.detach(this);
        }
        delegate.finish();
    }

    private static ScheduledExecutorService sharedScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = Executors.defaultThreadFactory().newThread(runnable);
            thread.setName("turn-window");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
