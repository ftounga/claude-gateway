package fr.claudegateway.quota;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import fr.claudegateway.billing.PlanCode;

/**
 * Configuration des quotas d'entitlement (F-10). Toutes les valeurs sont externalisées et
 * réversibles (ajustables par environnement sans changement de code) : elles décrivent la structure
 * commerciale (allocations de tokens), jamais un secret.
 *
 * @param trialTokens allocation mensuelle de tokens pendant l'essai gratuit
 * @param plans       allocation mensuelle de tokens par code de plan ({@link PlanCode} → tokens)
 */
@ConfigurationProperties(prefix = "app.quota")
public record QuotaProperties(
        Long trialTokens,
        Map<String, Long> plans) {

    private static final long DEFAULT_TRIAL_TOKENS = 200_000L;

    public QuotaProperties {
        if (trialTokens == null || trialTokens < 0) {
            trialTokens = DEFAULT_TRIAL_TOKENS;
        }
        if (plans == null) {
            plans = Map.of();
        } else {
            // Copie défensive : la map d'entitlements ne doit pas être mutée après binding.
            plans = new HashMap<>(plans);
        }
    }

    /**
     * Allocation mensuelle de tokens d'un plan, ou {@code 0} si le plan n'est pas configuré
     * (fail-closed : un plan sans quota configuré ne débloque aucun accès).
     */
    public long tokensForPlan(PlanCode code) {
        if (code == null) {
            return 0L;
        }
        Long tokens = plans.get(code.name());
        return tokens == null || tokens < 0 ? 0L : tokens;
    }
}
