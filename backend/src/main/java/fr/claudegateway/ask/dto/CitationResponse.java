package fr.claudegateway.ask.dto;

import java.util.UUID;

/**
 * Citation d'un chunk ayant servi de contexte à la réponse (F-07). Permet à l'utilisateur de tracer
 * l'origine documentaire de la réponse ({@code [filename:page:chunkIndex]}). Ne contient aucune donnée
 * d'un autre utilisateur (source filtrée {@code user_id} côté service).
 *
 * @param documentId identifiant du document source ({@code documents.id})
 * @param filename   nom du fichier source
 * @param page       numéro de page (peut être {@code null} : non dérivé en F-06)
 * @param chunkIndex position ordinale du chunk dans le document
 * @param snippet    extrait tronqué du chunk (aperçu lisible)
 */
public record CitationResponse(
        UUID documentId,
        String filename,
        Integer page,
        int chunkIndex,
        String snippet) {
}
