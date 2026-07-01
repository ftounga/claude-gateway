package fr.claudegateway.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Vue partielle de la réponse de la Files API d'Anthropic ({@code POST /v1/files}). Seul
 * l'identifiant du fichier est exploité par la Gateway. Interne à la couche fournisseur.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record AnthropicFileResponse(String id) {
}
