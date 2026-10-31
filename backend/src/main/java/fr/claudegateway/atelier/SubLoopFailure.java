package fr.claudegateway.atelier;

/**
 * <b>Une sous-boucle tombée en route, avec ce qu'elle avait déjà consommé</b> (F-224 / SF-224-06).
 *
 * <p>L'exploration et les sous-tâches ({@link AtelierExploration}, {@link AtelierTask}) additionnent leurs jetons
 * appel après appel et ne les rendent qu'à la fin. Une panne au troisième appel faisait donc disparaître les deux
 * premiers, pourtant facturés : l'appelant rendait une issue à zéro jeton. Cette exception enveloppe la cause
 * d'origine et porte la consommation déjà facturée ; l'appelant l'impute au tour <b>une seule fois</b> — le chemin
 * normal (le {@code Result}) n'est alors pas atteint, il n'y a pas de double compte.</p>
 *
 * <p>Elle ne porte aucun contenu : des nombres seulement. Le message est celui de la cause.</p>
 */
final class SubLoopFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int inputTokens;
    private final int outputTokens;
    private final int cacheReadTokens;
    private final int cacheWriteTokens;

    SubLoopFailure(RuntimeException cause, int inputTokens, int outputTokens, int cacheReadTokens,
            int cacheWriteTokens) {
        super(cause.getMessage(), cause, false, false);
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
    }

    /**
     * Ce qu'une sous-boucle a déjà consommé quand {@code failure} l'a arrêtée : sans consommation, la cause d'origine
     * remonte telle quelle ; sinon, l'enveloppe.
     */
    static RuntimeException wrap(RuntimeException failure, int inputTokens, int outputTokens, int cacheReadTokens,
            int cacheWriteTokens) {
        if (failure instanceof SubLoopFailure || inputTokens + outputTokens <= 0) {
            return failure;
        }
        return new SubLoopFailure(failure, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens);
    }

    /** La consommation portée par {@code failure} (ou par l'une de ses causes proches), sinon {@code null}. */
    static SubLoopFailure of(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 4; depth++, current = current.getCause()) {
            if (current instanceof SubLoopFailure lost) {
                return lost;
            }
        }
        return null;
    }

    int inputTokens() {
        return inputTokens;
    }

    int outputTokens() {
        return outputTokens;
    }

    int cacheReadTokens() {
        return cacheReadTokens;
    }

    int cacheWriteTokens() {
        return cacheWriteTokens;
    }
}
