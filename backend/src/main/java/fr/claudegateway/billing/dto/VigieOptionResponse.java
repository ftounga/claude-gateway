package fr.claudegateway.billing.dto;

import java.time.OffsetDateTime;

import fr.claudegateway.billing.VigieOptionService.VigieOptionView;

/**
 * État de l'option Vigie renvoyé à l'écran de facturation (F-107 / SF-107-03). Ne porte <b>aucun</b>
 * identifiant fournisseur.
 *
 * @param priceEur                 montant d'affichage EUR (ex. {@code "69"})
 * @param entitled                 vrai si l'utilisateur a le droit Vigie, quelle qu'en soit la source
 * @param includedInPlan           vrai si le droit vient de l'offre (Gold Vigie, Gold complet)
 * @param status                   statut de l'option, ou {@code null} si jamais souscrite
 * @param cancelAt                 terme d'une résiliation programmée, ou {@code null}
 * @param available                vrai si l'option est réellement souscriptible
 * @param includedForAdministrator vrai si le droit vient du rôle administrateur
 * @param goldCarrier              vrai si l'offre porteuse est Gold Forge (Gold complet est alors moins cher)
 */
public record VigieOptionResponse(
        String priceEur,
        boolean entitled,
        boolean includedInPlan,
        String status,
        OffsetDateTime cancelAt,
        boolean available,
        boolean includedForAdministrator,
        boolean goldCarrier) {

    /** Projette la vue métier vers le contrat REST. */
    public static VigieOptionResponse from(VigieOptionView view) {
        return new VigieOptionResponse(
                view.priceEur(),
                view.entitled(),
                view.includedInPlan(),
                view.optionStatus() == null ? null : view.optionStatus().name(),
                view.cancelAt(),
                view.available(),
                view.includedForAdministrator(),
                view.goldCarrier());
    }
}
