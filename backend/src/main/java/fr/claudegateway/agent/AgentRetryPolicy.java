package fr.claudegateway.agent;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Politique de réessai d'un appel de la boucle d'agent (F-39 / SF-39-11).
 *
 * <p>Logique <b>pure</b> et sans état : elle dit si un statut mérite d'être rejoué, et combien de
 * temps attendre avant de le faire. Elle n'appelle rien, n'attend rien — c'est l'appelant qui dort,
 * ce qui rend la règle testable sans horloge.</p>
 *
 * <p><b>Deux statuts, et deux seulement</b> (décision D-L6-2) : {@code 429} (trop de requêtes) et
 * {@code 529} (fournisseur surchargé) sont les refus que le fournisseur déclare temporaires. Un
 * {@code 500} est ambigu — la création de message a peut-être été traitée avant l'erreur, et la
 * rejouer ferait exécuter deux fois la même série d'outils sur la machine de l'utilisateur. Le coût
 * d'un faux positif ici n'est pas un token perdu : c'est une commande jouée deux fois.</p>
 */
final class AgentRetryPolicy {

    /** Refus temporaires du fournisseur, seuls statuts rejoués (D-L6-2). */
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 529);
    /** Premier repli exponentiel, en millisecondes. */
    static final long INITIAL_DELAY_MS = 1_000L;
    /** Plafond d'une attente unitaire, {@code Retry-After} compris. */
    static final long MAX_DELAY_MS = 30_000L;
    /**
     * Plafond de l'attente <b>cumulée</b> d'un appel (décision D-L6-4). Le budget de tour n'est
     * vérifié qu'entre deux itérations de la boucle, jamais pendant un appel : rien ne rattraperait
     * une attente de plusieurs minutes décidée par un {@code Retry-After} généreux.
     */
    static final long MAX_TOTAL_WAIT_MS = 60_000L;
    /** Valeur rendue par {@link #delayMs} quand il ne faut plus attendre du tout. */
    static final long NO_DELAY = -1L;

    private final int maxAttempts;

    AgentRetryPolicy(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /** Vrai si ce statut HTTP est un refus temporaire du fournisseur. */
    static boolean retryableStatus(int status) {
        return RETRYABLE_STATUSES.contains(status);
    }

    /**
     * Vrai si une tentative supplémentaire est permise après la {@code attempt}-ième (1-indexée).
     */
    boolean hasAttemptLeft(int attempt) {
        return attempt < maxAttempts;
    }

    /**
     * Attente à observer avant la tentative suivante, ou {@link #NO_DELAY} si le budget d'attente
     * cumulé est épuisé.
     *
     * @param attempt        numéro (1-indexé) de la tentative qui vient d'échouer
     * @param retryAfter     valeur brute de l'en-tête {@code Retry-After}, ou {@code null}
     * @param alreadyWaitedMs attente déjà consommée par cet appel
     */
    long delayMs(int attempt, String retryAfter, long alreadyWaitedMs) {
        if (alreadyWaitedMs >= MAX_TOTAL_WAIT_MS) {
            return NO_DELAY;
        }
        long delay = retryAfterMs(retryAfter);
        if (delay < 0) {
            delay = backoffMs(attempt);
        }
        delay = Math.min(delay, MAX_DELAY_MS);
        // L'attente restante peut être plus courte que le repli : mieux vaut une dernière tentative
        // rapide qu'aucune tentative.
        return Math.min(delay, MAX_TOTAL_WAIT_MS - alreadyWaitedMs);
    }

    /**
     * Repli exponentiel <b>avec gigue</b> (décision D-L6-5) : sans elle, tous les appels plafonnés
     * à la même seconde repartiraient ensemble et se re-plafonneraient ensemble. La gigue est bornée
     * à {@code [0,5 × d ; d]} — assez pour disperser, assez étroite pour rester testable.
     */
    private static long backoffMs(int attempt) {
        // Bornée avant le décalage : au-delà du plafond, la valeur exacte n'a plus d'importance.
        int steps = Math.min(Math.max(attempt, 1) - 1, 20);
        long base = Math.min(INITIAL_DELAY_MS << steps, MAX_DELAY_MS);
        long floor = base / 2;
        return floor + ThreadLocalRandom.current().nextLong(base - floor + 1);
    }

    /**
     * {@code Retry-After} en millisecondes, ou {@code -1} si l'en-tête est absent, vide, négatif ou
     * exprimé en date HTTP.
     *
     * <p>La forme « date » n'est pas interprétée : elle exigerait une horloge de référence commune
     * avec le fournisseur, et un décalage d'horloge produirait une attente absurde. Mieux vaut le
     * repli exponentiel, qui ne dépend de rien.</p>
     */
    private static long retryAfterMs(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return -1L;
        }
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            return seconds < 0 ? -1L : seconds * 1_000L;
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }
}
