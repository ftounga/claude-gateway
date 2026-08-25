package fr.claudegateway.atelier.dto;

/**
 * État de l'option « demander avant d'exécuter » après bascule (F-33 / SF-33-01).
 *
 * @param enabled                 l'option telle qu'elle est désormais enregistrée pour ce projet
 * @param appliesToCurrentSession faux si une sandbox est déjà ouverte : elle garde la politique
 *                                posée à son ouverture, et seule une réinitialisation appliquera la
 *                                nouvelle. Le dire évite de laisser croire à une protection qui
 *                                n'est pas encore en vigueur
 */
public record AgentConfirmationResponse(boolean enabled, boolean appliesToCurrentSession) {
}
