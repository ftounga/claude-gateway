package fr.claudegateway.export.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps de {@code POST /api/export/answer} (F-14 / SF-14-01). Endpoint <b>stateless</b> : la réponse
 * documentée (F-07 n'étant pas persistée) est renvoyée par l'appelant pour être rendue en
 * Markdown/PDF. Aucune donnée d'un autre utilisateur ne transite (l'appelant exporte sa propre
 * réponse).
 *
 * @param question  question posée (obligatoire, non vide)
 * @param answer    réponse à exporter (obligatoire, non vide)
 * @param model     modèle ayant produit la réponse (facultatif)
 * @param grounded  {@code true} si la réponse est ancrée sur des documents (facultatif)
 * @param citations sources citées (facultatif, éventuellement vide)
 */
public record AnswerExportRequest(
        @NotBlank String question,
        @NotBlank String answer,
        String model,
        Boolean grounded,
        List<AnswerCitation> citations) {

    /** Citations non nulles (liste vide si absente), pour un rendu robuste. */
    public List<AnswerCitation> safeCitations() {
        return citations == null ? List.of() : citations;
    }
}
