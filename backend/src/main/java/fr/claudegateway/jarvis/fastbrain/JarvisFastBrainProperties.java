package fr.claudegateway.jarvis.fastbrain;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>Le cerveau rapide de Jarvis</b> (F-235 / SF-235-01, D1, D15, D17) : le modèle qui tient la conversation vocale et
 * délègue à Opus tout ce qui agit ou demande réflexion.
 *
 * <p>Un seul constructeur (record compact) : un second casserait le démarrage du contexte Spring.</p>
 *
 * @param enabled           coupe-circuit (D17) : faux ⇒ {@code /api/jarvis/converse} répond 503 et l'écran garde le mode
 *                          d'avant (tout à Opus). Défaut faux jusqu'au banc (SF-235-08)
 * @param model             modèle rapide (défaut {@code claude-haiku-5-5} ; repli par configuration
 *                          {@code claude-sonnet-5-5})
 * @param effort            effort demandé (défaut {@code low})
 * @param maxTokens         plafond de sortie (défaut 600 : 1-3 phrases orales, ou un appel {@code deleguer})
 * @param monthlyBudgetEur  plafond mensuel propre, en euros (défaut 20) ; atteint ⇒ mode d'avant, sans erreur orale
 * @param firstTokenTimeout délai sans premier jeton au-delà duquel la demande est déléguée d'office (défaut 3 s, D9b)
 */
@ConfigurationProperties(prefix = "app.jarvis.fast-brain")
public record JarvisFastBrainProperties(
        Boolean enabled,
        String model,
        String effort,
        Integer maxTokens,
        BigDecimal monthlyBudgetEur,
        Duration firstTokenTimeout) {

    public static final String DEFAULT_MODEL = "claude-haiku-5-5";

    public JarvisFastBrainProperties {
        enabled = Boolean.TRUE.equals(enabled);
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        effort = effort == null || effort.isBlank() ? "low" : effort.strip();
        maxTokens = maxTokens == null || maxTokens <= 0 ? 600 : maxTokens;
        monthlyBudgetEur = monthlyBudgetEur == null || monthlyBudgetEur.signum() < 0 ? new BigDecimal("20")
                : monthlyBudgetEur;
        firstTokenTimeout = firstTokenTimeout == null || firstTokenTimeout.isNegative() || firstTokenTimeout.isZero()
                ? Duration.ofSeconds(3) : firstTokenTimeout;
    }

    /** Réglages d'un environnement qui ne configure rien : éteint. */
    public static JarvisFastBrainProperties defaults() {
        return new JarvisFastBrainProperties(null, null, null, null, null, null);
    }

    /** Vrai si le cerveau rapide est allumé. */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
