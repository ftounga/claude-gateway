package fr.claudegateway.vigie.dto;

import java.util.List;

/**
 * La check-list de mise en service de la Vigie pour un poste (F-122 / SF-122-02).
 *
 * @param checks              les quatre vérifications et leur statut
 * @param canStart            vrai seulement si les quatre sont {@code OK} — sinon on ne démarre pas
 * @param teamsSignInRequired vrai si Teams demande une identification (bouton « Se connecter à Teams »)
 */
public record VigieReadinessResponse(
        List<VigieReadinessItem> checks,
        boolean canStart,
        boolean teamsSignInRequired) {
}
