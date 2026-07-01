package fr.claudegateway.ask;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages du Q&A documentaire (F-07), externalisés pour être ajustables sans changement de code
 * (arbitrages réversibles : nombre de chunks de contexte, taille des extraits de citation).
 *
 * @param topK           nombre de chunks de contexte récupérés par défaut (borné {@code [1, MAX_TOP_K]})
 * @param snippetMaxChars longueur maximale d'un extrait de citation (caractères)
 */
@ConfigurationProperties(prefix = "app.rag.ask")
public record AskProperties(Integer topK, Integer snippetMaxChars) {

    /** Défaut : 5 chunks de contexte (compromis rappel/latence, réévaluable — OQ-03). */
    public static final int DEFAULT_TOP_K = 5;
    /** Borne haute de sécurité pour éviter des prompts démesurés / coûts d'appel. */
    public static final int MAX_TOP_K = 20;
    /** Défaut : extrait de 240 caractères par citation. */
    public static final int DEFAULT_SNIPPET_MAX_CHARS = 240;

    public AskProperties {
        if (topK == null || topK < 1) {
            topK = DEFAULT_TOP_K;
        }
        if (topK > MAX_TOP_K) {
            topK = MAX_TOP_K;
        }
        if (snippetMaxChars == null || snippetMaxChars < 1) {
            snippetMaxChars = DEFAULT_SNIPPET_MAX_CHARS;
        }
    }

    /** {@code topK} par défaut effectif. */
    public int effectiveTopK() {
        return topK;
    }

    /**
     * Borne un {@code topK} demandé par le client dans {@code [1, MAX_TOP_K]}, avec repli sur le défaut
     * si absent/invalide.
     */
    public int clampTopK(Integer requested) {
        if (requested == null || requested < 1) {
            return effectiveTopK();
        }
        return Math.min(requested, MAX_TOP_K);
    }
}
