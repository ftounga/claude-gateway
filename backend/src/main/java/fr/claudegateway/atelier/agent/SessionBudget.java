package fr.claudegateway.atelier.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Plafond de dépense <b>dur</b> posé à la création d'une session Managed Agents (F-36 / SF-36-01).
 *
 * <p>Le fournisseur applique un <b>verrou pré-requête</b> : avant chaque appel au modèle il compare
 * le cumul facturé (tokens au tarif du modèle servi, recherches web, temps de bac à sable) au
 * plafond, et met le thread en pause si le plafond est atteint. Il <b>empêche</b> la dépense, là où
 * le contrôle de quota (F-10) ne fait que la constater après coup.</p>
 *
 * <p>Le montant est exprimé en <b>unités mineures</b> (cents) : c'est la forme attendue par le
 * fournisseur, qui rejette les formes décimales. Le budget est <b>création-seule</b> — il ne peut
 * pas être ajouté à une session déjà ouverte.</p>
 *
 * @param amountMinorUnits montant du plafond en unités mineures (strictement positif)
 * @param currency         devise ISO 4217 du plafond (ex. {@code USD})
 */
public record SessionBudget(long amountMinorUnits, String currency) {

    /** Devise des tarifs publics du fournisseur : le budget est libellé en dollars. */
    public static final String USD = "USD";

    public SessionBudget {
        if (amountMinorUnits <= 0) {
            throw new IllegalArgumentException("Le plafond de dépense d'une session doit être positif.");
        }
        if (currency == null || currency.isBlank()) {
            currency = USD;
        }
    }

    /**
     * Plafond en dollars, arrondi <b>au cent inférieur</b> : arrondir au supérieur autoriserait à
     * dépenser au-delà de ce qui a été calculé.
     *
     * @param amount montant en dollars (strictement positif)
     * @return le plafond correspondant, en unités mineures
     */
    public static SessionBudget ofUsd(BigDecimal amount) {
        long minorUnits = amount.movePointRight(2).setScale(0, RoundingMode.DOWN).longValueExact();
        return new SessionBudget(minorUnits, USD);
    }

    /**
     * Montant tel qu'il est transmis au fournisseur : les unités mineures, <b>en chaîne</b>
     * ({@code "200"} = 2,00 $). Une forme décimale serait rejetée.
     *
     * @return le montant en chaîne
     */
    public String amountAsString() {
        return Long.toString(amountMinorUnits);
    }
}
