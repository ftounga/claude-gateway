package fr.claudegateway.billing;

/**
 * Mode fournisseur associé à un plan d'abonnement (F-09).
 *
 * <ul>
 *   <li>{@link #HOSTED} — consommation via la clé plateforme (mode Hosted, PROJECT.md §11.7).</li>
 *   <li>{@link #BYOK} — consommation via la clé personnelle de l'utilisateur (PROJECT.md §11.8).</li>
 * </ul>
 */
public enum ProviderMode {
    HOSTED,
    BYOK
}
