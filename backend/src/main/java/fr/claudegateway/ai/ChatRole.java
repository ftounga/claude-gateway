package fr.claudegateway.ai;

/**
 * Rôle d'un message dans un échange avec le fournisseur IA, indépendant de tout fournisseur.
 * Volontairement distinct des rôles de l'API Anthropic : la traduction est faite par
 * l'implémentation {@link AIProvider} concernée.
 */
public enum ChatRole {
    USER,
    ASSISTANT
}
