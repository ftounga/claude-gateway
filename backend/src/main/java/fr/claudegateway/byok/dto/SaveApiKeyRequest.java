package fr.claudegateway.byok.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Requête d'ajout/mise à jour de la clé BYOK. La clé n'est jamais journalisée ni renvoyée en clair.
 *
 * @param apiKey clé API personnelle en clair (validée puis chiffrée côté service)
 */
public record SaveApiKeyRequest(
        @NotBlank
        @Size(max = 200)
        String apiKey) {
}
