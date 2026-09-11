package fr.claudegateway.billing.dto;

import fr.claudegateway.billing.TopUpPack;

/**
 * Projection d'un pack de tokens du catalogue (F-21 / SF-21-02, enrichie par F-67).
 *
 * <p>Depuis F-67 elle porte le <b>montant d'affichage</b> du pack, exactement comme
 * {@link PlanResponse} porte celui d'un plan : sans lui, l'écran de rachat n'avait aucun prix à
 * montrer et le client découvrait le montant sur la page de paiement. Le <b>price ID Stripe reste
 * interne</b> — c'est lui qui porte le débit, et il n'a rien à faire dans une réponse d'API.</p>
 *
 * @param code     code stable du pack
 * @param label    libellé affichable
 * @param tokens   nombre de tokens crédités à l'achat
 * @param priceEur montant d'affichage en EUR (ex. {@code "4,99"}), ou {@code null} si aucun montant
 *                 n'est configuré. {@code null} est un état <b>normal</b> et non une erreur : le pack
 *                 reste vendable, l'écran dit simplement que le prix sera indiqué au paiement. Il ne
 *                 faut jamais y substituer un montant par défaut — un chiffre inventé à côté d'un
 *                 bouton d'achat est opposable par un client.
 */
public record TopUpPackResponse(String code, String label, long tokens, String priceEur) {

    public static TopUpPackResponse of(TopUpPack pack, String priceEur) {
        return new TopUpPackResponse(pack.code(), pack.label(), pack.tokens(), priceEur);
    }
}
