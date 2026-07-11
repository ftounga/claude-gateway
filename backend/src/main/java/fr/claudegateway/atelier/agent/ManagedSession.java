package fr.claudegateway.atelier.agent;

/**
 * Session Managed Agents créée chez le fournisseur (F-28 / Phase 2, ADR-013). Éphémère : créée par
 * tâche d'atelier, puis terminée. Coût runtime facturé tant qu'elle est active — d'où le
 * {@code finally} de terminaison côté orchestration.
 *
 * @param id identifiant fournisseur de la session
 */
public record ManagedSession(String id) {
}
