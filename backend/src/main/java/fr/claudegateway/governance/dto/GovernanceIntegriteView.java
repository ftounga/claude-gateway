package fr.claudegateway.governance.dto;

import java.util.List;
import java.util.UUID;

/**
 * <b>L'intégrité d'un poste, telle que l'écran la montre</b> (F-95 / SF-95-03).
 *
 * <p>Les deux niveaux arrivent <b>séparés</b>, et c'est tout l'intérêt : l'écran n'a rien à trier, et
 * ne peut donc pas présenter un avertissement comme un refus. C'est l'exigence littérale de la
 * feature, portée jusque dans la forme du contrat.</p>
 *
 * <p><b>{@code inspected} à faux n'est pas « tout va bien »</b> : machine muette, poste sans
 * gouvernance, poste sans racine. Deux listes vides et {@code inspected} à vrai, c'est un poste
 * inspecté et sain ; deux listes vides et {@code inspected} à faux, c'est un poste dont on n'a rien
 * lu. Les confondre ferait afficher une bonne nouvelle qu'on n'a pas vérifiée.</p>
 *
 * @param hostRef        le poste, tel qu'il s'écrit dans une URL
 * @param hostId         son identifiant, ou {@code null} pour le poste « Hébergé » (F-71)
 * @param inspected      faux si rien n'a pu être inspecté
 * @param errors         les constats <b>bloquants</b>, dans l'ordre de découverte
 * @param warnings       les constats <b>informatifs</b>, dans l'ordre de découverte
 */
public record GovernanceIntegriteView(String hostRef, UUID hostId, boolean inspected,
        List<GovernanceIntegriteConstatView> errors,
        List<GovernanceIntegriteConstatView> warnings) {

    /** Constats rendus par niveau. Au-delà, ce n'est plus un relevé, c'est un inventaire. */
    public static final int MAX_PAR_NIVEAU = 20;

    /** Rend les listes immuables et bornées. */
    public GovernanceIntegriteView {
        errors = borne(errors);
        warnings = borne(warnings);
    }

    private static List<GovernanceIntegriteConstatView> borne(
            List<GovernanceIntegriteConstatView> constats) {
        if (constats == null || constats.isEmpty()) {
            return List.of();
        }
        return constats.size() <= MAX_PAR_NIVEAU ? List.copyOf(constats)
                : List.copyOf(constats.subList(0, MAX_PAR_NIVEAU));
    }
}
