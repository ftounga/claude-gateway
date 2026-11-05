package fr.claudegateway.agent;

import fr.claudegateway.ai.AIProviderException;

/**
 * Signal neutre (F-172 / SF-172-07) : un appel en flux s'est <b>emballé deux fois</b> — raisonnement
 * caché sans rien de visible au-delà du seuil, coupé, relancé à l'identique, et coupé de nouveau.
 *
 * <p>Aucun outil n'a été exécuté : la coupure n'intervient que tant que l'appel n'a produit ni texte,
 * ni note, ni bloc d'outil, et aucun tour n'en est sorti. L'usage des deux appels coupés est porté
 * ici pour être compté comme celui de tout appel — rien n'est caché.</p>
 */
public class AgentRunawayException extends AIProviderException {

    private final int inputTokens;
    private final int outputTokens;
    private final int cacheReadTokens;
    private final int cacheWriteTokens;

    /**
     * @param inputTokens      entrée totale traitée (cache compris), comme {@link AgentTurn#inputTokens()}
     * @param outputTokens     sortie produite (rapportée ou estimée)
     * @param cacheReadTokens  part de l'entrée lue en cache
     * @param cacheWriteTokens part de l'entrée écrite en cache
     */
    public AgentRunawayException(String message, int inputTokens, int outputTokens, int cacheReadTokens,
            int cacheWriteTokens) {
        super(message);
        this.inputTokens = Math.max(0, inputTokens);
        this.outputTokens = Math.max(0, outputTokens);
        this.cacheReadTokens = Math.max(0, cacheReadTokens);
        this.cacheWriteTokens = Math.max(0, cacheWriteTokens);
    }

    public int inputTokens() {
        return inputTokens;
    }

    public int outputTokens() {
        return outputTokens;
    }

    public int cacheReadTokens() {
        return cacheReadTokens;
    }

    public int cacheWriteTokens() {
        return cacheWriteTokens;
    }
}
