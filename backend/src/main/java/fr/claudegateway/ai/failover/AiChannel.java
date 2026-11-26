package fr.claudegateway.ai.failover;

/**
 * Canal d'accès qui a servi un appel au modèle Claude (F-277). La valeur est rangée dans
 * {@code usage_turns.provider} et {@code atelier_turn_measures.provider}.
 */
public enum AiChannel {
    /** API Anthropic directe (clé plateforme ou clé BYOK). */
    ANTHROPIC,
    /** AWS Bedrock, profil d'inférence {@code global.anthropic.*} (ADR-022). */
    BEDROCK
}
