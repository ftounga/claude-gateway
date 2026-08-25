package fr.claudegateway.git;

/**
 * Référence du vault de credentials d'un utilisateur chez le fournisseur d'agents
 * (F-31 / SF-31-05).
 *
 * <p>Deux identifiants opaques, <b>aucun secret</b> : le jeton déposé dans ce vault est write-only
 * côté fournisseur et n'est jamais relu.</p>
 *
 * @param vaultId      identifiant du vault ({@code vlt_…})
 * @param credentialId identifiant de la credential qui y porte le jeton ({@code vcrd_…}), ou
 *                     {@code null} si elle n'a pas été mémorisée
 */
public record GitVaultRef(String vaultId, String credentialId) {
}
