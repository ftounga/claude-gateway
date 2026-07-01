package fr.claudegateway.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Contraintes du pipeline d'ingestion RAG (F-06). Externalisées pour être ajustables sans changement
 * de code (arbitrages réversibles : taille de chunk, overlap, sélection du stockage vectoriel).
 *
 * @param vectorStore stockage vectoriel actif : {@code noop} (défaut, dev/tests/H2) ou {@code pgvector} (cluster)
 * @param chunk       paramètres de découpage
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(String vectorStore, Chunk chunk) {

    /** Défauts imposés par la mini-spec F-06 : 400 « tokens » / overlap 50. */
    public static final int DEFAULT_MAX_TOKENS = 400;
    public static final int DEFAULT_OVERLAP_TOKENS = 50;

    /**
     * @param maxTokens     taille cible d'un chunk en « tokens » (mots approximés)
     * @param overlapTokens chevauchement entre chunks consécutifs (0 ≤ overlap < maxTokens)
     */
    public record Chunk(Integer maxTokens, Integer overlapTokens) {
    }

    public RagProperties {
        if (vectorStore == null || vectorStore.isBlank()) {
            vectorStore = "noop";
        }
        if (chunk == null) {
            chunk = new Chunk(DEFAULT_MAX_TOKENS, DEFAULT_OVERLAP_TOKENS);
        }
    }

    /** Taille de chunk effective (défaut si non/incorrectement configurée). */
    public int effectiveMaxTokens() {
        Integer value = chunk.maxTokens();
        return value != null && value > 0 ? value : DEFAULT_MAX_TOKENS;
    }

    /** Overlap effectif, borné dans {@code [0, maxTokens[} (défaut si non/incorrectement configuré). */
    public int effectiveOverlapTokens() {
        int max = effectiveMaxTokens();
        Integer value = chunk.overlapTokens();
        int overlap = value != null && value >= 0 ? value : DEFAULT_OVERLAP_TOKENS;
        return Math.min(overlap, max - 1);
    }
}
