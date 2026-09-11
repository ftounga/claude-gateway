package fr.claudegateway.ai;

/**
 * Résultat d'une complétion renvoyée par un {@link AIProvider}.
 *
 * @param content          texte de la réponse assistant
 * @param model            modèle effectivement utilisé (tel que rapporté par le fournisseur)
 * @param inputTokens      tokens consommés en entrée, cache compris (0 si non rapporté)
 * @param outputTokens     tokens consommés en sortie (0 si non rapporté)
 * @param cacheReadTokens  part de {@code inputTokens} servie depuis le cache (F-63 / SF-63-02) :
 *                         un dixième du tarif d'entrée. Vaut 0 tant que la passerelle ne pose pas
 *                         de {@code cache_control} — le décompte est alors strictement celui
 *                         d'avant
 * @param cacheWriteTokens part de {@code inputTokens} écrite dans le cache, au tarif majoré
 */
public record ChatCompletionResult(String content, String model, int inputTokens, int outputTokens,
        int cacheReadTokens, int cacheWriteTokens) {

    /** Forme sans ventilation de cache — conservée pour les appelants qui l'attendent. */
    public ChatCompletionResult(String content, String model, int inputTokens, int outputTokens) {
        this(content, model, inputTokens, outputTokens, 0, 0);
    }

    /** Tokens d'entrée facturés au plein tarif : le total rapporté, moins le cache. */
    public int fullPriceInputTokens() {
        return Math.max(0, inputTokens - cacheReadTokens - cacheWriteTokens);
    }

    /** Consommation du tour, ventilée par nature, telle que le quota la décompte (F-63). */
    public fr.claudegateway.quota.TurnTokens turnTokens() {
        return new fr.claudegateway.quota.TurnTokens(
                fullPriceInputTokens(), outputTokens, cacheReadTokens, cacheWriteTokens);
    }
}
