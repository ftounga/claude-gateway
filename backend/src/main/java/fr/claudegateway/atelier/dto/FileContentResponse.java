package fr.claudegateway.atelier.dto;

/** Contenu texte d'un fichier du workspace. */
public record FileContentResponse(String path, String content) {
}
