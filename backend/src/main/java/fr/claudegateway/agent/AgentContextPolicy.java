package fr.claudegateway.agent;

/**
 * Politique de contexte d'un tour d'agent (F-39 / SF-39-12). Neutre vis-à-vis du fournisseur : elle
 * dit <b>quoi écarter</b> d'un contexte devenu trop long, pas comment l'API l'exprime — le mapping
 * est confiné à {@code AnthropicAgentProvider} (Provider Independence), comme celui de
 * {@link AgentReasoning}.
 *
 * <p><b>Écarter, pas résumer</b> (décision D-L6-7) : les résultats d'outils les plus anciens sont
 * retirés du contexte, la structure de la conversation restant intacte. Résumer reviendrait à
 * décider à la place de l'agent ce qui comptait ; écarter la sortie d'une commande vieille de vingt
 * itérations ne perd que ce qu'il a déjà exploité.</p>
 *
 * <p>Ce qui déborde est <b>intra-tour</b> : à chaque itération, la boucle réempile les
 * {@code tool_use} et leurs résultats dans la même liste de messages, et une seule sortie de
 * commande pèse jusqu'à 128 Ko. La mémoire d'un message à l'autre, elle, est déjà bornée
 * (SF-39-03).</p>
 *
 * @param pruneToolResults       vrai si les résultats d'outils périmés doivent être écartés
 * @param triggerInputTokens     volume de contexte d'entrée à partir duquel l'écartement s'applique
 * @param keepRecentToolResults  nombre de résultats récents toujours conservés
 * @param clearAtLeastInputTokens plancher d'écartement : en deçà, ne rien écarter. Une édition
 *                               modifie le préfixe et invalide donc le cache de prompt (SF-39-01) ;
 *                               sans plancher, le remède coûterait plus que le mal (D-L6-9)
 */
public record AgentContextPolicy(boolean pruneToolResults, int triggerInputTokens,
        int keepRecentToolResults, int clearAtLeastInputTokens) {

    private static final AgentContextPolicy NONE = new AgentContextPolicy(false, 0, 0, 0);

    /** Aucune édition demandée : le tour part exactement comme avant cette subfeature. */
    public static AgentContextPolicy none() {
        return NONE;
    }
}
