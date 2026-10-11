package fr.claudegateway.atelier.plume;

import java.util.List;

/**
 * Réponse de {@code POST /workspaces/{id}/enrich-prompt} (F-193 / SF-193-01) — contrat figé, consommé
 * par l'écran (SF-193-02).
 *
 * @param enrichedPrompt la demande structurée, prête à relire et à éditer ; rien n'est envoyé
 * @param toConfirm      ce que le modèle a supposé, à confirmer par l'utilisateur (jamais {@code null})
 * @param sources        ce qui a été lu pour enrichir, en toutes lettres (jamais {@code null})
 */
public record EnrichPromptResponse(String enrichedPrompt, List<String> toConfirm, List<String> sources) {

    public EnrichPromptResponse {
        toConfirm = toConfirm == null ? List.of() : List.copyOf(toConfirm);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
