package fr.claudegateway.quota;

import java.math.BigDecimal;

/**
 * Ce qu'un tour a <b>réellement coûté</b>, et d'où le montant vient (F-133 / SF-133-01).
 *
 * <p>La provenance est portée avec le montant, jamais déduite après coup : un coût rapporté par le
 * fournisseur et un coût reconstitué à partir des tokens n'ont pas la même autorité, et le jour où
 * l'on comparera nos totaux à la facture, c'est cette distinction qui dira où chercher l'écart.</p>
 *
 * @param amountUsd       montant en dollars, jamais négatif
 * @param source          d'où vient le montant
 * @param model           modèle servi, tel que rapporté par le fournisseur, ou {@code null}
 * @param pricingVersion  date de la grille de tarifs utilisée
 * @param pricingFallback {@code true} si le modèle était inconnu de la grille et que les tarifs de
 *                        repli ont servi — le montant reste exploitable, mais il est approché
 */
public record TurnCost(
        BigDecimal amountUsd,
        Source source,
        String model,
        String pricingVersion,
        boolean pricingFallback) {

    /** D'où vient le montant. */
    public enum Source {
        /** Reconstitué à partir des tokens, aux tarifs de la grille. */
        CALCULATED,
        /** Rapporté par le fournisseur lui-même : il sait des choses que les tokens ignorent. */
        PROVIDER
    }

    public TurnCost {
        amountUsd = amountUsd == null || amountUsd.signum() < 0 ? BigDecimal.ZERO : amountUsd;
        model = model == null || model.isBlank() ? null : model.trim();
    }
}
