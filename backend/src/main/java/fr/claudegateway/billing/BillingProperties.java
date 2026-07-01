package fr.claudegateway.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du module billing (F-09). Toutes les valeurs sont externalisées. Les secrets Stripe
 * (clé secrète, secret de webhook) et les price IDs sont introduits en SF-09-02 ; SF-09-01 n'a besoin
 * que de la durée d'essai.
 *
 * @param trialDays durée de l'essai gratuit en jours (défaut 14, PROJECT.md §11.10)
 */
@ConfigurationProperties(prefix = "app.billing")
public record BillingProperties(Integer trialDays) {

    public BillingProperties {
        if (trialDays == null || trialDays <= 0) {
            trialDays = 14;
        }
    }
}
