package fr.claudegateway.runner.diag;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * <b>Le collecteur d'événements de diagnostic du runner</b> (F-132 / SF-132-01).
 *
 * <p>Registre <b>statique</b>, dans l'esprit de {@code RunnerActivity} : n'importe quel point du
 * runner note un événement sans qu'on ait à lui injecter une dépendance — l'instrumentation reste
 * <b>additive</b> et ne traverse aucun constructeur. {@link RunnerDiagEmitter} draine ensuite ce
 * registre par lots et l'émet en trame {@code runner_diag} sur le WebSocket existant.</p>
 *
 * <p><b>Deux invariants.</b></p>
 * <ul>
 *   <li><b>Best-effort strict</b> : {@link #event} et ses raccourcis n'échouent jamais. Une entrée
 *       aberrante est avalée — le fonctionnement du runner n'est jamais impacté par la
 *       journalisation (cadrage §2).</li>
 *   <li><b>Expurgation à la source</b> : message et champs repassent par {@link RunnerDiagRedaction}
 *       avant d'entrer dans l'anneau — aucun secret, aucune URL brute, aucun contenu ne peut fuir,
 *       même si un appelant oublie d'expurger.</li>
 * </ul>
 *
 * <p>L'anneau est <b>borné</b> : au-delà de {@link #CAPACITY}, le plus ancien est écarté et un
 * compteur {@code dropped} est tenu, remonté dans la trame suivante. Le seuil de niveau ({@code INFO}
 * par défaut) filtre à l'entrée ; il est réglable (SF-132-05) via {@link #setLevel}.</p>
 */
public final class RunnerDiag {

    /** Nombre d'événements retenus en mémoire avant écrasement du plus ancien (cadrage §5–6). */
    public static final int CAPACITY = 500;

    /** Niveau de base auquel on revient à l'expiration d'un réglage temporaire (défaut confirmé PO). */
    private static final RunnerDiagLevel BASE_LEVEL = RunnerDiagLevel.INFO;

    private static final AtomicReference<RunnerDiagLevel> LEVEL =
            new AtomicReference<>(RunnerDiagLevel.INFO);
    private static final Deque<RunnerDiagEvent> RING = new ArrayDeque<>(CAPACITY);
    private static final Object LOCK = new Object();
    private static long dropped;
    /** Échéance (ms épochales) d'un réglage temporaire ; {@code 0} = pas d'expiration. */
    private static volatile long revertAtMillis;
    /** Horloge, injectable pour les tests (SF-132-05). */
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private RunnerDiag() {
    }

    /**
     * Le seuil courant : un événement n'est retenu que si son niveau l'atteint. Constate d'abord
     * l'expiration d'un éventuel réglage temporaire (revert paresseux — SF-132-05).
     */
    public static RunnerDiagLevel level() {
        expireTemporaryIfDue();
        return LEVEL.get();
    }

    /**
     * Règle le seuil de façon <b>permanente</b> : annule tout réglage temporaire en cours. Une valeur
     * nulle est ignorée.
     */
    public static void setLevel(RunnerDiagLevel level) {
        if (level != null) {
            synchronized (LOCK) {
                LEVEL.set(level);
                revertAtMillis = 0;
            }
        }
    }

    /**
     * Règle le seuil <b>temporairement</b> (SF-132-05 : passage en {@code DEBUG} le temps d'un
     * diagnostic). À l'expiration du délai, le seuil <b>revient automatiquement</b> à
     * {@link #BASE_LEVEL} — bascule <b>paresseuse</b>, constatée au prochain événement/relevé (aucun
     * ordonnanceur). Un {@code ttlSeconds} nul ou négatif règle le seuil sans expiration. Une valeur de
     * niveau nulle est ignorée. Ne lève jamais.
     */
    public static void setTemporaryLevel(RunnerDiagLevel level, long ttlSeconds) {
        if (level == null) {
            return;
        }
        synchronized (LOCK) {
            LEVEL.set(level);
            revertAtMillis = ttlSeconds > 0 ? clock.getAsLong() + ttlSeconds * 1_000L : 0;
        }
    }

    /** Constate l'expiration d'un réglage temporaire et revient au niveau de base le cas échéant. */
    private static void expireTemporaryIfDue() {
        if (revertAtMillis == 0) {
            return;
        }
        synchronized (LOCK) {
            if (revertAtMillis != 0 && clock.getAsLong() >= revertAtMillis) {
                LEVEL.set(BASE_LEVEL);
                revertAtMillis = 0;
            }
        }
    }

    /**
     * Note un événement. Ne lève <b>jamais</b> : toute erreur d'expurgation ou d'insertion est
     * avalée. Filtré par le seuil courant, expurgé, puis rangé dans l'anneau borné.
     */
    public static void event(RunnerDiagLevel level, String cat, String code, String msg,
            Map<String, ?> fields) {
        try {
            if (level == null || !level.reaches(level())) {
                return;
            }
            String safeCat = RunnerDiagRedaction.label(cat);
            String safeCode = RunnerDiagRedaction.label(code);
            if (safeCat == null || safeCode == null) {
                return;
            }
            RunnerDiagEvent e = new RunnerDiagEvent(
                    Instant.now(),
                    level,
                    safeCat,
                    safeCode,
                    RunnerDiagRedaction.message(msg),
                    RunnerDiagRedaction.fields(fields));
            offer(e);
        } catch (RuntimeException ignored) {
            // La journalisation ne casse jamais le runner (cadrage §2) : on avale tout.
        }
    }

    /** Raccourci {@code DEBUG}. */
    public static void debug(String cat, String code, String msg, Map<String, ?> fields) {
        event(RunnerDiagLevel.DEBUG, cat, code, msg, fields);
    }

    /** Raccourci {@code INFO}. */
    public static void info(String cat, String code, String msg, Map<String, ?> fields) {
        event(RunnerDiagLevel.INFO, cat, code, msg, fields);
    }

    /** Raccourci {@code WARN}. */
    public static void warn(String cat, String code, String msg, Map<String, ?> fields) {
        event(RunnerDiagLevel.WARN, cat, code, msg, fields);
    }

    /** Raccourci {@code ERROR}. */
    public static void error(String cat, String code, String msg, Map<String, ?> fields) {
        event(RunnerDiagLevel.ERROR, cat, code, msg, fields);
    }

    /**
     * Retire jusqu'à {@code max} événements (les plus anciens d'abord) et le nombre d'événements
     * écartés depuis le dernier drainage. Vide l'anneau d'autant. Appelé par {@link RunnerDiagEmitter}.
     */
    public static Drained drain(int max) {
        synchronized (LOCK) {
            List<RunnerDiagEvent> batch = new ArrayList<>(Math.min(max, RING.size()));
            while (batch.size() < max && !RING.isEmpty()) {
                batch.add(RING.pollFirst());
            }
            long droppedNow = dropped;
            dropped = 0;
            return new Drained(batch, droppedNow);
        }
    }

    /** Vrai s'il n'y a rien à drainer (ni événement, ni perte à signaler). */
    public static boolean isEmpty() {
        synchronized (LOCK) {
            return RING.isEmpty() && dropped == 0;
        }
    }

    /** Oublie tout (tests, et à l'arrêt du runner). */
    public static void reset() {
        synchronized (LOCK) {
            RING.clear();
            dropped = 0;
            LEVEL.set(RunnerDiagLevel.INFO);
            revertAtMillis = 0;
            clock = System::currentTimeMillis;
        }
    }

    /** Remplace l'horloge (tests uniquement) pour éprouver le retour automatique à INFO. */
    static void setClock(LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
    }

    private static void offer(RunnerDiagEvent e) {
        synchronized (LOCK) {
            if (RING.size() >= CAPACITY) {
                RING.pollFirst();
                dropped++;
            }
            RING.addLast(e);
        }
    }

    /**
     * Le résultat d'un drainage : le lot d'événements et le nombre écarté par débordement de
     * l'anneau depuis le dernier drainage.
     */
    public record Drained(List<RunnerDiagEvent> events, long dropped) {
    }
}
