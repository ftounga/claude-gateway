package fr.claudegateway.byok;

/**
 * Fournisseur IA associé à une clé BYOK. En V1, seul Anthropic est supporté (Provider Independence :
 * l'énumération prépare l'ajout d'autres fournisseurs sans changer le schéma).
 */
public enum ByokProvider {
    ANTHROPIC
}
