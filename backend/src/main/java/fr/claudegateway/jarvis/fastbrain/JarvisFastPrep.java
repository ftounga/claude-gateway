package fr.claudegateway.jarvis.fastbrain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import fr.claudegateway.jarvis.cards.JarvisCardCatalog;

/**
 * <b>La préparation d'une réplique du cerveau rapide</b> (F-252 / SF-252-01, correctif B) : les lectures indépendantes
 * (budget, contexte, univers, portrait, allusions, clé, réglages) partent <b>ensemble</b> sur des fils virtuels, puis
 * sont <b>consommées dans l'ordre d'avant</b>, sur le fil de la décision.
 *
 * <p>Rien ne change de ce que reçoit le modèle : chaque lecture est la même méthode qu'avant ; une lecture en échec
 * est refaite sur le fil de la décision (même exception, même repli qu'en série) ; les fiches de pancartes lues sur un
 * fil parallèle sont reversées au catalogue de la réplique, dans l'ordre où la lecture en série les aurait rangées
 * ({@link JarvisCardCatalog#absorb}).</p>
 */
final class JarvisFastPrep {

    /** Au-delà, la lecture est refaite sur le fil de la décision (un fil parallèle bloqué ne fige jamais la réplique). */
    static final long JOIN_TIMEOUT_MS = 2_000L;

    /**
     * Lectures de préparation simultanées sur ce pod (toutes répliques confondues) : le pool de connexions est de 10,
     * la préparation n'en prend jamais toute la place.
     */
    static final int MAX_CONCURRENT_READS = 6;
    private static final Semaphore READS = new Semaphore(MAX_CONCURRENT_READS);

    private final ExecutorService pool;
    private final boolean parallel;
    private final long startedNanos = System.nanoTime();
    private final List<Early<?>> launched = new ArrayList<>();

    /** @param parallel faux (coupe-circuit) : chaque lecture est faite à sa place, en série, comme avant */
    JarvisFastPrep(ExecutorService pool, boolean parallel) {
        this.pool = pool;
        this.parallel = parallel;
    }

    /** Le temps écoulé depuis l'entrée dans la décision, en ms. */
    long elapsedMs() {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    /**
     * Lance {@code body} tout de suite sur un fil parallèle ; {@code inline} est la même lecture, refaite sur le fil de
     * la décision si la première échoue ou tarde.
     */
    <T> Early<T> early(Supplier<T> body, Supplier<T> inline) {
        if (!parallel) {
            return new Early<>(null, inline);
        }
        boolean cards = JarvisCardCatalog.active();
        Future<Carried<T>> future = pool.submit(() -> {
            READS.acquire();
            JarvisCardCatalog child = cards ? JarvisCardCatalog.open() : null;
            try {
                return new Carried<>(body.get(), child);
            } finally {
                if (child != null) {
                    JarvisCardCatalog.close();
                }
                READS.release();
            }
        });
        Early<T> early = new Early<>(future, inline);
        launched.add(early);
        return early;
    }

    /** Abandonne les lectures non consommées (la réplique s'arrête avant de s'en servir). */
    void cancelUnused() {
        launched.forEach(Early::cancelIfUnused);
    }

    private record Carried<T>(T value, JarvisCardCatalog cards) {
    }

    /** Une lecture partie en avance ; {@link #get} la consomme (une fois, sur le fil de la décision). */
    static final class Early<T> {
        private final Future<Carried<T>> future;
        private final Supplier<T> inline;
        private boolean taken;
        private T value;

        private Early(Future<Carried<T>> future, Supplier<T> inline) {
            this.future = future;
            this.inline = inline;
        }

        T get() {
            if (taken) {
                return value;
            }
            taken = true;
            if (future == null) {
                value = inline.get();
                return value;
            }
            Carried<T> carried;
            try {
                carried = future.get(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                value = inline.get();
                return value;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                future.cancel(true);
                value = inline.get();
                return value;
            }
            JarvisCardCatalog parent = JarvisCardCatalog.current();
            if (parent != null) {
                parent.absorb(carried.cards());
            }
            value = carried.value();
            return value;
        }

        private void cancelIfUnused() {
            if (!taken && future != null) {
                future.cancel(true);
            }
        }
    }
}
