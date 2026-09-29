package fr.claudegateway.atelier.dto;

/**
 * Résultat d'une <b>compaction manuelle</b> du fil (F-162 / SF-162-04) : le geste « Compacter
 * maintenant ». Réponse de {@code POST /workspaces/{id}/chat/compact}.
 *
 * <p><b>Compaction douce</b> : contrairement au « Nouveau départ » ({@code /restart}) qui efface le
 * résumé et replie tout l'historique, ce geste <b>conserve le résumé</b> — il résume les vieux tours
 * pour alléger le contexte vif tout en gardant la mémoire.</p>
 *
 * @param compacted       vrai si une compaction a réellement eu lieu (des tours anciens ont été
 *                        résumés) ; faux quand il n'y avait rien à compacter ou que l'appel de synthèse
 *                        a échoué (best-effort : le fil reste alors intact)
 * @param summarizedTurns nombre de tours (messages {@code USER}) résumés — {@code 0} quand rien n'a
 *                        été écrit ; c'est ce N qu'affiche le marqueur « Conversation compactée · N
 *                        tours résumés » (même notion de tour que {@code recall} / SF-162-02/03)
 */
public record AtelierCompactResponse(boolean compacted, int summarizedTurns) {
}
