package fr.claudegateway.atelier.dto;

/**
 * Corps de {@code PUT /workspaces/{id}/file} : nouveau contenu texte du fichier.
 *
 * @param content contenu (peut être vide pour vider le fichier ; jamais {@code null} attendu)
 */
public record WriteFileRequest(String content) {
}
