package fr.claudegateway.export.dto;

import java.util.UUID;

/**
 * Citation d'une source à faire figurer dans l'export d'une réponse documentée (F-14).
 * Miroir du contrat {@code CitationResponse} de F-07 renvoyé par l'appelant (endpoint stateless).
 *
 * @param documentId identifiant du document source (peut être {@code null})
 * @param filename   nom du fichier source
 * @param page       numéro de page (peut être {@code null})
 * @param chunkIndex position ordinale du chunk dans le document
 * @param snippet    extrait lisible du chunk
 */
public record AnswerCitation(
        UUID documentId,
        String filename,
        Integer page,
        Integer chunkIndex,
        String snippet) {
}
