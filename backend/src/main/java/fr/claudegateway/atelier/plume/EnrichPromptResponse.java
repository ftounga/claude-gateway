package fr.claudegateway.atelier.plume;

import java.util.List;

/**
 * Réponse de {@code POST /workspaces/{id}/enrich-prompt} (F-193 / SF-193-01) — contrat figé, consommé
 * par l'écran (SF-193-02) ; {@code mode} ajouté par F-215 / SF-215-05 (rétrocompatible).
 *
 * @param enrichedPrompt la demande structurée, prête à relire et à éditer ; rien n'est envoyé
 * @param toConfirm      ce que le modèle a supposé, à confirmer par l'utilisateur (jamais {@code null})
 * @param sources        ce qui a été lu pour enrichir, en toutes lettres (jamais {@code null})
 * @param mode           {@value #MODE_VIDEO} pour un cahier de réalisation, {@value #MODE_PROMPT} sinon
 */
public record EnrichPromptResponse(String enrichedPrompt, List<String> toConfirm, List<String> sources, String mode) {

    /** Une demande enrichie (F-193). */
    public static final String MODE_PROMPT = "prompt";
    /** Un cahier de réalisation vidéo (F-215 / SF-215-05). */
    public static final String MODE_VIDEO = "video";

    public EnrichPromptResponse {
        toConfirm = toConfirm == null ? List.of() : List.copyOf(toConfirm);
        sources = sources == null ? List.of() : List.copyOf(sources);
        mode = MODE_VIDEO.equals(mode) ? MODE_VIDEO : MODE_PROMPT;
    }

    /** La forme historique (F-193) : une demande enrichie. */
    public EnrichPromptResponse(String enrichedPrompt, List<String> toConfirm, List<String> sources) {
        this(enrichedPrompt, toConfirm, sources, MODE_PROMPT);
    }
}
