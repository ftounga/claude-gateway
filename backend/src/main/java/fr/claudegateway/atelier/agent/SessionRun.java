package fr.claudegateway.atelier.agent;

/**
 * Résultat d'un run de session Managed Agents (F-28 / Phase 2, ADR-013), agrégé par polling des
 * events jusqu'à l'état {@code session.status_idle}.
 *
 * @param reply      texte final agrégé des events {@code agent.message}
 * @param stopReason raison d'arrêt rapportée par le fournisseur (ou {@code null} si absente)
 */
public record SessionRun(String reply, String stopReason) {
}
