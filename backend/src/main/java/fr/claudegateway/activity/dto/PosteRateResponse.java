package fr.claudegateway.activity.dto;

import java.util.UUID;

import fr.claudegateway.activity.PosteBilling;

/**
 * Le TJM d'un poste rendu à l'écran (F-124 / SF-124-01) : l'identifiant du poste et le TJM en
 * centimes d'euro HT.
 */
public record PosteRateResponse(UUID hostId, long dailyRateCents) {

    public static PosteRateResponse from(PosteBilling billing) {
        return new PosteRateResponse(billing.getHostId(), billing.getDailyRateCents());
    }
}
