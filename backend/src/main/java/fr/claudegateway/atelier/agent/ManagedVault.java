package fr.claudegateway.atelier.agent;

/**
 * Vault de credentials créé chez le fournisseur d'agents (F-31 / SF-31-05).
 *
 * <p>Deux identifiants opaques. Le secret déposé dedans est <b>write-only</b> côté fournisseur : il
 * n'est jamais renvoyé par l'API, jamais relu par nous, jamais journalisé.</p>
 *
 * @param vaultId      identifiant du vault ({@code vlt_…})
 * @param credentialId identifiant de la credential déposée ({@code vcrd_…})
 */
public record ManagedVault(String vaultId, String credentialId) {
}
