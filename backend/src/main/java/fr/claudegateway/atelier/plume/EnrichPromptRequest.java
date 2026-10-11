package fr.claudegateway.atelier.plume;

/**
 * Corps de {@code POST /workspaces/{id}/enrich-prompt} (F-193 / SF-193-01).
 *
 * @param text la phrase courte saisie par l'utilisateur, non vide, au plus 4 000 caractères
 */
public record EnrichPromptRequest(String text) {
}
