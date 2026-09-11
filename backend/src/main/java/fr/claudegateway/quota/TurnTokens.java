package fr.claudegateway.quota;

/**
 * Les tokens d'un tour, <b>par nature</b> (F-63). Quatre natures, quatre tarifs : l'entrée, la
 * sortie, ce qui a été <b>lu</b> du cache et ce qui y a été <b>écrit</b>.
 *
 * <p>Avant F-63 le décompte n'en connaissait que deux, et les additionnait à poids égal. Le cache,
 * lui, n'existait nulle part : les fournisseurs le repliaient dans l'entrée (décision D3 de
 * SF-39-01, « le quota mesure ce qui a été traité »), si bien qu'un tour massivement relu du cache
 * était facturé au plein tarif d'entrée alors qu'il coûtait un dixième.</p>
 *
 * <p>Les <b>volumes</b> restent ce qu'ils étaient : {@link #processedInputTokens()} additionne
 * l'entrée et les deux natures de cache, exactement comme les compteurs de période le faisaient
 * déjà. Seul le <b>coût</b> distingue les quatre.</p>
 *
 * @param inputTokens      tokens d'entrée facturés au plein tarif (hors cache)
 * @param outputTokens     tokens de sortie
 * @param cacheReadTokens  tokens servis depuis le cache
 * @param cacheWriteTokens tokens écrits dans le cache
 */
public record TurnTokens(long inputTokens, long outputTokens, long cacheReadTokens,
        long cacheWriteTokens) {

    /** Tour sans cache — forme des chemins qui n'en rapportent pas ({@code /chat}, {@code /ask}). */
    public static TurnTokens of(long inputTokens, long outputTokens) {
        return new TurnTokens(inputTokens, outputTokens, 0L, 0L);
    }

    public TurnTokens {
        // Un compteur négatif n'existe pas : un fournisseur qui en rapporte un s'est trompé, et le
        // décompte ne doit ni crédit ni exception.
        inputTokens = Math.max(0L, inputTokens);
        outputTokens = Math.max(0L, outputTokens);
        cacheReadTokens = Math.max(0L, cacheReadTokens);
        cacheWriteTokens = Math.max(0L, cacheWriteTokens);
    }

    /**
     * Volume d'entrée <b>traité</b> : entrée directe + lectures + écritures de cache. C'est ce que
     * les compteurs de période et le journal par tour enregistrent depuis toujours — F-63 n'y touche
     * pas, sous peine de faire mentir le rapport d'usage (F-16) et la consommation par client (F-61).
     */
    public long processedInputTokens() {
        return inputTokens + cacheReadTokens + cacheWriteTokens;
    }

    /** Vrai quand le tour n'a rien consommé du tout : il n'y a alors rien à enregistrer. */
    public boolean isEmpty() {
        return processedInputTokens() == 0L && outputTokens == 0L;
    }
}
