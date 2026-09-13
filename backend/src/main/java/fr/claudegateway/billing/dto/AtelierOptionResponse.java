package fr.claudegateway.billing.dto;

import java.time.OffsetDateTime;

import fr.claudegateway.billing.AtelierOptionService.AtelierOptionView;

/**
 * État de l'option Atelier renvoyé à l'écran de facturation (F-40). Ne porte <b>aucun</b>
 * identifiant fournisseur : le price ID et l'identifiant d'abonnement restent internes.
 *
 * @param priceEur       montant d'affichage EUR (ex. {@code "40"}), issu de la configuration
 * @param entitled       vrai si l'utilisateur a le droit d'accès à l'Atelier, quelle qu'en soit la source
 * @param includedInPlan vrai si le droit vient de l'offre elle-même (Gold) : l'option est sans objet
 * @param status         statut de l'option ({@code ACTIVE}, {@code CANCELED}…), ou {@code null} si jamais souscrite
 * @param cancelAt       terme d'une résiliation programmée, ou {@code null}
 * @param available      vrai si l'option est réellement souscriptible sur l'offre de l'utilisateur
 *                       (paiement et price de ce plan porteur configurés)
 * @param byokCarrier    vrai si l'offre de l'utilisateur est BYOK (F-107 / SF-107-01) : le montant
 *                       est celui de l'option sur BYOK, et {@code available=false} y veut dire
 *                       « pas encore proposée sur BYOK »
 * @param includedForAdministrator vrai si le droit vient du rôle administrateur (F-107 / SF-107-06) :
 *                       rien n'est facturé, l'écran dit « incluse (administrateur) »
 */
public record AtelierOptionResponse(
        String priceEur,
        boolean entitled,
        boolean includedInPlan,
        String status,
        OffsetDateTime cancelAt,
        boolean available,
        boolean byokCarrier,
        boolean includedForAdministrator) {

    /** Projette la vue métier vers le contrat REST. */
    public static AtelierOptionResponse from(AtelierOptionView view) {
        return new AtelierOptionResponse(
                view.priceEur(),
                view.entitled(),
                view.includedInPlan(),
                view.optionStatus() == null ? null : view.optionStatus().name(),
                view.cancelAt(),
                view.available(),
                view.byokCarrier(),
                view.includedForAdministrator());
    }
}
