package fr.claudegateway.shared.error;

/**
 * Corps d'erreur JSON homogène renvoyé par toute l'API : {@code {"error": "...", "message": "..."}}.
 *
 * @param error   code d'erreur stable et machine-lisible (ex. {@code unauthorized})
 * @param message message lisible destiné au client, sans détail d'implémentation ni secret
 */
public record ErrorResponse(String error, String message) {
}
