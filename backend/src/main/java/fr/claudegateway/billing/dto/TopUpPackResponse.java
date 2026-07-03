package fr.claudegateway.billing.dto;

import fr.claudegateway.billing.TopUpPack;

/**
 * Projection d'un pack de tokens du catalogue (F-21 / SF-21-02). N'expose ni prix ni price ID Stripe :
 * le prix vit côté fournisseur ; ici on ne décrit que la structure produit.
 *
 * @param code   code stable du pack
 * @param label  libellé affichable
 * @param tokens nombre de tokens crédités à l'achat
 */
public record TopUpPackResponse(String code, String label, long tokens) {

    public static TopUpPackResponse from(TopUpPack pack) {
        return new TopUpPackResponse(pack.code(), pack.label(), pack.tokens());
    }
}
