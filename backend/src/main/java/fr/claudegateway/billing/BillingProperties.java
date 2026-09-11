package fr.claudegateway.billing;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Configuration du module billing (F-09). Toutes les valeurs sont externalisées ; les secrets Stripe
 * (clé secrète, secret de webhook) proviennent exclusivement de l'environnement et ne sont jamais
 * journalisés (patron identique à la clé Anthropic).
 *
 * @param trialDays durée de l'essai gratuit en jours (défaut 5, PROJECT.md §11.10)
 * @param stripe    réglages du fournisseur de paiement Stripe (SF-09-02)
 */
@ConfigurationProperties(prefix = "app.billing")
public record BillingProperties(
        Integer trialDays,
        @NestedConfigurationProperty Stripe stripe) {

    public BillingProperties {
        if (trialDays == null || trialDays <= 0) {
            trialDays = 5;
        }
        if (stripe == null) {
            stripe = new Stripe(
                    null, null, Map.of(), Map.of(), null, null, Map.of(), null, null,
                    Map.of(), Map.of(), Map.of());
        }
    }

    /**
     * Réglages Stripe. Le mapping {@code prices} associe un code de plan ({@link PlanCode}) à un
     * price ID Stripe (défini par environnement, jamais en dur — OQ-07).
     *
     * @param secretKey     clé secrète Stripe (env {@code STRIPE_SECRET_KEY}) — vide => fournisseur dormant (503)
     * @param webhookSecret secret de vérification de signature webhook (env {@code STRIPE_WEBHOOK_SECRET})
     * @param prices        code de plan → price ID Stripe
     * @param topupPrices   code de pack de tokens (top-up, F-21) → price ID Stripe
     * @param successUrl    URL de retour après paiement réussi
     * @param cancelUrl     URL de retour après annulation
     * @param displayPrices code de plan → montant d'affichage EUR (cosmétique, SF-21-05)
     * @param atelierOptionPriceId     price ID de l'<b>option Atelier</b> (F-40) — vide => option non souscriptible
     * @param atelierOptionDisplayPrice montant d'affichage EUR de l'option Atelier (cosmétique, défaut 40)
     * @param yearlyPrices        code de plan → price ID Stripe <b>annuel</b> (F-43) — vide => pas d'engagement annuel
     * @param yearlyDisplayPrices code de plan → montant d'affichage EUR <b>annuel</b> (cosmétique, F-43)
     * @param topupDisplayPrices  code de pack de recharge → montant d'affichage EUR (cosmétique, F-67).
     *                            Même patron que {@code displayPrices} : le débit réel appartient au
     *                            price ID, jamais à ce montant. Un pack sans entrée ici est vendable
     *                            <b>sans prix affiché</b> — l'écran le dit, il n'invente rien.
     */
    public record Stripe(
            String secretKey,
            String webhookSecret,
            Map<String, String> prices,
            Map<String, String> topupPrices,
            String successUrl,
            String cancelUrl,
            Map<String, String> displayPrices,
            String atelierOptionPriceId,
            String atelierOptionDisplayPrice,
            Map<String, String> yearlyPrices,
            Map<String, String> yearlyDisplayPrices,
            Map<String, String> topupDisplayPrices) {

        public Stripe {
            if (prices == null) {
                prices = Map.of();
            }
            if (topupPrices == null) {
                topupPrices = Map.of();
            }
            if (displayPrices == null) {
                displayPrices = Map.of();
            }
            if (yearlyPrices == null) {
                yearlyPrices = Map.of();
            }
            if (yearlyDisplayPrices == null) {
                yearlyDisplayPrices = Map.of();
            }
            if (topupDisplayPrices == null) {
                topupDisplayPrices = Map.of();
            }
            if (successUrl == null || successUrl.isBlank()) {
                successUrl = "http://localhost:4200/billing?checkout=success";
            }
            if (cancelUrl == null || cancelUrl.isBlank()) {
                cancelUrl = "http://localhost:4200/billing?checkout=cancel";
            }
            if (atelierOptionDisplayPrice == null || atelierOptionDisplayPrice.isBlank()) {
                // Le défaut de la feature (40 €/mois) vit aussi ici : une configuration incomplète
                // ne doit pas afficher un prix vide à côté d'un bouton d'achat.
                atelierOptionDisplayPrice = "40";
            }
        }

        /** Vrai si une clé secrète est configurée (fournisseur réellement appelable). */
        public boolean isConfigured() {
            return secretKey != null && !secretKey.isBlank();
        }

        /** Price ID Stripe associé au plan, ou {@code null} si non configuré. */
        public String priceId(PlanCode code) {
            return prices == null ? null : prices.get(code.name());
        }

        /** Price ID Stripe associé à un pack de tokens (top-up), ou {@code null} si non configuré. */
        public String topupPriceId(String packCode) {
            return topupPrices == null ? null : topupPrices.get(packCode);
        }

        /**
         * Montant d'affichage (EUR) d'un pack de recharge (F-67), ou {@code null} si aucun montant
         * n'est configuré pour ce pack.
         *
         * <p>Le {@code null} est la réponse <b>voulue</b> quand la configuration est muette : il vaut
         * mieux qu'un écran dise « prix indiqué au paiement » que d'afficher un montant que personne
         * n'a décidé. Une valeur blanche est donc traitée comme une absence — sans quoi elle
         * traverserait l'API et s'afficherait « €» à côté d'un bouton d'achat.</p>
         */
        public String topupDisplayPrice(String packCode) {
            if (packCode == null || topupDisplayPrices == null) {
                return null;
            }
            String price = topupDisplayPrices.get(packCode);
            return price == null || price.isBlank() ? null : price;
        }

        /** Montant d'affichage (EUR) du plan pour la page de facturation, ou {@code null} si absent. */
        public String displayPrice(PlanCode code) {
            return displayPrices == null ? null : displayPrices.get(code.name());
        }

        /**
         * Price ID Stripe <b>annuel</b> du plan (F-43), ou {@code null} si l'engagement annuel n'est
         * pas configuré pour ce plan.
         */
        public String yearlyPriceId(PlanCode code) {
            return code == null ? null : yearlyPrices.get(code.name());
        }

        /**
         * Montant d'affichage (EUR) <b>annuel</b> du plan (F-43), ou {@code null} si absent.
         * Cosmétique : le débit réel est porté par le price annuel.
         */
        public String yearlyDisplayPrice(PlanCode code) {
            return code == null ? null : yearlyDisplayPrices.get(code.name());
        }

        /**
         * Vrai si ce plan est réellement proposable à l'année (F-43). Trois conditions, et les trois
         * comptent :
         *
         * <ul>
         *   <li>le plan est un <b>abonnement mensuel</b> : un pass journée est un paiement unique
         *       chez le fournisseur, l'annualiser n'aurait aucun sens — et la règle se lit sur la
         *       période native du plan, jamais sur un code de plan écrit en dur ;</li>
         *   <li>un <b>price ID annuel</b> est configuré : sans lui, le paiement répondrait 503 ;</li>
         *   <li>un <b>montant d'affichage annuel</b> est configuré : sans lui, l'écran présenterait
         *       un bouton d'achat sans prix.</li>
         * </ul>
         *
         * <p>Une offre à moitié configurée n'est donc jamais proposée, dans un sens comme dans
         * l'autre.</p>
         */
        public boolean isYearlyAvailable(Plan plan) {
            if (plan == null || plan.period() != BillingPeriod.MONTHLY) {
                return false;
            }
            String priceId = yearlyPriceId(plan.code());
            String displayPrice = yearlyDisplayPrice(plan.code());
            return priceId != null && !priceId.isBlank()
                    && displayPrice != null && !displayPrice.isBlank();
        }

        /**
         * Vrai si l'option Atelier (F-40) est réellement souscriptible : fournisseur configuré
         * <b>et</b> price ID d'option renseigné. Sans les deux, l'option est dormante (503).
         */
        public boolean isAtelierOptionConfigured() {
            return isConfigured() && atelierOptionPriceId != null && !atelierOptionPriceId.isBlank();
        }
    }
}
