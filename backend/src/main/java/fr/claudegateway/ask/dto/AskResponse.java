package fr.claudegateway.ask.dto;

import java.util.List;

/**
 * Réponse de {@code POST /api/ask} (F-07). Contrat figé (importé tel quel par SF-07-02 frontend).
 *
 * @param answer    réponse de Claude
 * @param model     modèle effectivement utilisé
 * @param grounded  {@code true} si au moins un chunk a servi de contexte ; {@code false} en repli
 * @param citations citations des chunks utilisés (vide en repli)
 */
public record AskResponse(
        String answer,
        String model,
        boolean grounded,
        List<CitationResponse> citations) {
}
