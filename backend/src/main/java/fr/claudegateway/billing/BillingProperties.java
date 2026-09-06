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
            stripe = new Stripe(null, null, Map.of(), Map.of(), null, null, Map.of(), null, null);
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
            String atelierOptionDisplayPrice) {

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

        /** Montant d'affichage (EUR) du plan pour la page de facturation, ou {@code null} si absent. */
        public String displayPrice(PlanCode code) {
            return displayPrices == null ? null : displayPrices.get(code.name());
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
