package fr.claudegateway.billing;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Configuration du module billing (F-09). Toutes les valeurs sont externalisées ; les secrets Stripe
 * (clé secrète, secret de webhook) proviennent exclusivement de l'environnement et ne sont jamais
 * journalisés (patron identique à la clé Anthropic).
 *
 * @param trialDays durée de l'essai gratuit en jours (défaut 14, PROJECT.md §11.10)
 * @param stripe    réglages du fournisseur de paiement Stripe (SF-09-02)
 */
@ConfigurationProperties(prefix = "app.billing")
public record BillingProperties(
        Integer trialDays,
        @NestedConfigurationProperty Stripe stripe) {

    public BillingProperties {
        if (trialDays == null || trialDays <= 0) {
            trialDays = 14;
        }
        if (stripe == null) {
            stripe = new Stripe(null, null, Map.of(), null, null);
        }
    }

    /**
     * Réglages Stripe. Le mapping {@code prices} associe un code de plan ({@link PlanCode}) à un
     * price ID Stripe (défini par environnement, jamais en dur — OQ-07).
     *
     * @param secretKey     clé secrète Stripe (env {@code STRIPE_SECRET_KEY}) — vide => fournisseur dormant (503)
     * @param webhookSecret secret de vérification de signature webhook (env {@code STRIPE_WEBHOOK_SECRET})
     * @param prices        code de plan → price ID Stripe
     * @param successUrl    URL de retour après paiement réussi
     * @param cancelUrl     URL de retour après annulation
     */
    public record Stripe(
            String secretKey,
            String webhookSecret,
            Map<String, String> prices,
            String successUrl,
            String cancelUrl) {

        public Stripe {
            if (prices == null) {
                prices = Map.of();
            }
            if (successUrl == null || successUrl.isBlank()) {
                successUrl = "http://localhost:4200/billing?checkout=success";
            }
            if (cancelUrl == null || cancelUrl.isBlank()) {
                cancelUrl = "http://localhost:4200/billing?checkout=cancel";
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
    }
}
