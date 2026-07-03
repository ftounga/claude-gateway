package fr.claudegateway.billing.dto;

import java.util.List;

import fr.claudegateway.billing.TopUpPack;

/**
 * Enveloppe de réponse du catalogue de packs de tokens (F-21 / SF-21-02).
 *
 * @param packs liste des packs rachetables
 */
public record TopUpPacksResponse(List<TopUpPackResponse> packs) {

    public static TopUpPacksResponse from(List<TopUpPack> packs) {
        return new TopUpPacksResponse(packs.stream().map(TopUpPackResponse::from).toList());
    }
}
