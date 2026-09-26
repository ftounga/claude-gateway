package fr.claudegateway.bilan;

/**
 * <b>La part de cache, calculée à un seul endroit</b> (F-155 / SF-155-06).
 *
 * <p><b>La sémantique qu'il faut connaître</b> : {@code usage_turns.input_tokens} <b>contient déjà
 * le cache</b>. Ce n'est pas une supposition — c'est ce que fait le code qui l'alimente :
 * {@code TurnTokens.processedInputTokens()} additionne « entrée directe + lectures + écritures de
 * cache », et {@code AnthropicAgentProvider} écrit {@code input_tokens + cacheCreation + cacheRead}.</p>
 *
 * <p><b>Le défaut que cette classe répare</b> : trois endroits divisaient par
 * {@code input + cacheRead}, comptant donc le cache <b>deux fois</b> et divisant la part par
 * environ deux. Sur la session mesurée du 25/09 (74 tours, 81,70 $), la formule annonçait
 * <b>46 %</b> là où la réalité est <b>86 %</b> — de quoi déclencher le détecteur « cache froid » sur
 * une session dont le cache est sain. <b>Un bilan qui se trompe de moitié sur son chiffre principal
 * est pire qu'un bilan absent</b> : on corrige ce qui va bien.</p>
 *
 * <p>Une seule implémentation, parce qu'un défaut recopié trois fois se re-recopiera tant qu'il y
 * aura trois formules.</p>
 */
public final class CacheShare {

    private CacheShare() {
    }

    /**
     * La part, de 0 à 100, des jetons d'entrée servis depuis le cache.
     *
     * @param inputTokens     volume d'entrée <b>traité</b> — cache compris
     * @param cacheReadTokens jetons servis depuis le cache, inclus dans le précédent
     */
    public static int of(long inputTokens, long cacheReadTokens) {
        if (inputTokens <= 0 || cacheReadTokens <= 0) {
            return 0;
        }
        long share = Math.round(100.0 * cacheReadTokens / inputTokens);
        // Une donnée aberrante ne doit pas produire 120 % : on plafonne plutôt que de laisser
        // passer un chiffre que personne ne croira — et qui ferait douter du reste du bilan.
        return (int) Math.min(100L, share);
    }
}
