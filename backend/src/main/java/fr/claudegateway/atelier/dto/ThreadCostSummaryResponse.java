package fr.claudegateway.atelier.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * L'<b>économie du fil courant</b> (F-165 / SF-165-02) : ce que la commande vue {@code /cout} affiche,
 * <b>sans aucun tour modèle</b>. Une vue sur NOS données ({@code usage_turns} + état de reprise),
 * isolée {@code user_id} et propriété du workspace — jamais le coût d'un autre.
 *
 * <p><b>Des volumes et des montants, jamais un contenu</b> : ni message, ni commande, ni chemin. La
 * garantie de F-61 (aucun texte ne transite par le journal de consommation) est tenue jusqu'ici.</p>
 *
 * <p>« Fil courant » = les tours depuis le dernier <b>nouveau départ</b> ({@code threadStartedAt} de
 * {@code /resume}) ; sans frontière, tout le projet.</p>
 *
 * @param currency        devise d'affichage (ex. {@code EUR}) — l'euro est une commodité de lecture,
 *                        le fournisseur facture en dollars (SF-133-02)
 * @param cumulativeEur   coût cumulé du fil, en euros
 * @param lastTurnEur     coût du dernier tour du fil, en euros
 * @param turnCount       nombre de tours facturés du fil
 * @param breakdown       décomposition écriture / lecture / sortie (montants et parts, somme 100 %)
 * @param hotCachePercent part de l'entrée traitée servie <b>à chaud</b> depuis le cache
 *                        ({@code cache_read / input}, entier %) — 0 si aucune entrée
 * @param contextTokens   taille du contexte <b>vivant</b> (proxy : entrée traitée du dernier tour)
 * @param contextPages    ce contexte en « pages » (≈ {@code tokens / 500}), langage « classeur »
 * @param liveTurns       tours <b>vivants</b> (rejouables) — depuis {@code /resume}
 * @param foldedTurns     tours <b>rangés</b> (repliés derrière « voir l'historique ») — depuis
 *                        {@code /resume}
 * @param trendEur        coût par tour des derniers tours, du plus ancien au plus récent (mini-tendance)
 */
public record ThreadCostSummaryResponse(
        String currency,
        BigDecimal cumulativeEur,
        BigDecimal lastTurnEur,
        int turnCount,
        Breakdown breakdown,
        int hotCachePercent,
        long contextTokens,
        int contextPages,
        int liveTurns,
        int foldedTurns,
        List<BigDecimal> trendEur) {

    /**
     * Les trois postes réels d'un fil agentique, en euros et en parts entières (somme 100 %). L'entrée
     * neuve (hors cache), marginale quand la boucle écrit tout dans le cache, est agrégée au poste
     * <b>écriture</b> pour que la décomposition soit exhaustive.
     *
     * @param writeEur      coût d'écriture (entrée neuve + écriture de cache), en euros
     * @param writePercent  part d'écriture (%)
     * @param readEur       coût de lecture de cache, en euros
     * @param readPercent   part de lecture (%)
     * @param outputEur     coût de sortie, en euros
     * @param outputPercent part de sortie (%)
     */
    public record Breakdown(
            BigDecimal writeEur,
            int writePercent,
            BigDecimal readEur,
            int readPercent,
            BigDecimal outputEur,
            int outputPercent) {
    }
}
