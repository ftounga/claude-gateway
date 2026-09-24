package fr.claudegateway.push.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import fr.claudegateway.push.PushSubscription;

/**
 * Abonnement d'un appareil au Web Push (F-153 / SF-153-02). L'identité vient <b>toujours</b> du
 * jeton (jamais du corps) : cette requête ne porte que l'adresse push et les deux clés cliente.
 *
 * @param endpoint URL du service push du navigateur
 * @param keys     clés cliente ({@code p256dh}, {@code auth}) issues de {@code PushSubscription}
 */
public record PushSubscribeRequest(
        @NotBlank @Size(max = PushSubscription.MAX_ENDPOINT_LENGTH) String endpoint,
        @NotNull @Valid Keys keys) {

    /**
     * Les deux clés cliente du protocole Web Push (RFC 8291).
     *
     * @param p256dh clé publique cliente (base64url)
     * @param auth   secret d'authentification (base64url)
     */
    public record Keys(
            @NotBlank @Size(max = PushSubscription.MAX_KEY_LENGTH) String p256dh,
            @NotBlank @Size(max = PushSubscription.MAX_KEY_LENGTH) String auth) {
    }
}
