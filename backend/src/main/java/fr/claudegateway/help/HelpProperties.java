package fr.claudegateway.help;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages du chatbot d'aide produit (F-54 / SF-54-01).
 *
 * @param maxQuestions nombre de questions autorisées par utilisateur sur la fenêtre glissante
 * @param window       durée de la fenêtre glissante du garde-fou de débit
 * @param maxTokens    plafond de tokens de sortie d'une réponse d'aide
 */
@ConfigurationProperties(prefix = "app.help")
public record HelpProperties(Integer maxQuestions, Duration window, Integer maxTokens) {

    /** Questions par fenêtre à défaut de configuration. */
    public static final int DEFAULT_MAX_QUESTIONS = 20;
    /** Fenêtre glissante à défaut de configuration. */
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(60);
    /**
     * Plafond de sortie d'une réponse d'aide. Une réponse d'aide est courte par construction (3 à 6
     * phrases) : la borne est une garantie de coût autant qu'une consigne de style.
     */
    public static final int DEFAULT_MAX_TOKENS = 512;

    public HelpProperties {
        // Une valeur aberrante en configuration ne doit pas empêcher le démarrage : elle est
        // ramenée à son défaut, comme ailleurs dans le projet (cf. AnthropicProperties).
        if (maxQuestions == null || maxQuestions < 1) {
            maxQuestions = DEFAULT_MAX_QUESTIONS;
        }
        if (window == null || window.isZero() || window.isNegative()) {
            window = DEFAULT_WINDOW;
        }
        if (maxTokens == null || maxTokens < 1) {
            maxTokens = DEFAULT_MAX_TOKENS;
        }
    }
}
