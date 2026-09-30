package fr.claudegateway.atelier.dto;

/**
 * L'<b>état mémoire du fil courant</b> (F-165 / SF-165-03) : ce que la commande vue {@code /contexte}
 * affiche, <b>sans aucun tour modèle</b>. Une vue sur NOS données ({@code usage_turns} + état de reprise
 * + réglages de compaction + présence d'un résumé ancré), isolée {@code user_id} et propriété du
 * workspace — jamais l'état d'un autre.
 *
 * <p><b>Des volumes, des drapeaux et un seuil, jamais un contenu</b> : ni message, ni résumé, ni chemin.
 * On expose que le fil <b>a</b> un résumé ancré, jamais le résumé lui-même.</p>
 *
 * <p>« Fil courant » = les tours depuis le dernier <b>nouveau départ</b> ({@code threadStartedAt} de
 * {@code /resume}) ; sans frontière, tout le projet.</p>
 *
 * @param contextTokens      taille du contexte <b>vivant</b> (proxy : entrée traitée du dernier tour) —
 *                           cohérent avec {@code /cout}
 * @param contextPages       ce contexte en « pages » (≈ {@code tokens / 500}), langage « classeur »
 * @param liveTurns          tours <b>vivants</b> (rejouables) — depuis {@code /resume}
 * @param foldedTurns        tours <b>rangés</b> (repliés derrière « voir l'historique ») — depuis
 *                           {@code /resume}
 * @param hasAnchoredSummary le fil a-t-il un <b>résumé ancré</b> (mémoire longue de la compaction douce,
 *                           F-162) ? Présence seulement — le résumé lui-même ne sort jamais
 * @param compactionEnabled  la compaction automatique est-elle active (F-117) ?
 * @param triggerTokens      seuil de tokens au-delà duquel la compaction se déclenche (défaut 120 000)
 * @param triggerPages       ce seuil en « pages » (≈ {@code tokens / 500})
 * @param fillPercent        progression du contexte vivant vers le seuil (0–100 %)
 * @param keepRecentTurns    nombre de messages récents gardés entiers au rejeu (F-117)
 * @param recallSemantic     le rappel <b>sémantique</b> (embeddings, F-162) est-il actif ? Le rappel par
 *                           mot-clé, lui, couvre toujours tout le fil
 */
public record ThreadContextSummaryResponse(
        long contextTokens,
        int contextPages,
        int liveTurns,
        int foldedTurns,
        boolean hasAnchoredSummary,
        boolean compactionEnabled,
        int triggerTokens,
        int triggerPages,
        int fillPercent,
        int keepRecentTurns,
        boolean recallSemantic) {
}
