package fr.claudegateway.quota;

import java.math.BigDecimal;

/**
 * Ce qu'une origine ({@code GATEWAY} ou {@code JARVIS}) a dépensé sur une fenêtre (F-244 / SF-244-02) : volumes et coût
 * réel, en dollars. Projection d'une seule ligne ; zéro partout sans dépense.
 */
public interface OriginSpend {

    Long getInputTokens();

    Long getOutputTokens();

    BigDecimal getCostUsd();

    /** Entrée, jamais {@code null}. */
    default long inputTokens() {
        return getInputTokens() == null ? 0L : getInputTokens();
    }

    /** Sortie, jamais {@code null}. */
    default long outputTokens() {
        return getOutputTokens() == null ? 0L : getOutputTokens();
    }

    /** Coût réel en dollars, jamais {@code null}. */
    default BigDecimal costUsd() {
        return getCostUsd() == null ? BigDecimal.ZERO : getCostUsd();
    }
}
