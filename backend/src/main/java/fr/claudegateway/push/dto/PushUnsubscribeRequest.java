package fr.claudegateway.push.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import fr.claudegateway.push.PushSubscription;

/**
 * Désabonnement d'un appareil (F-153 / SF-153-02). Borné au {@code user_id} du jeton : on ne retire
 * que sa propre ligne pour cet endpoint.
 *
 * @param endpoint URL du service push à retirer
 */
public record PushUnsubscribeRequest(
        @NotBlank @Size(max = PushSubscription.MAX_ENDPOINT_LENGTH) String endpoint) {
}
