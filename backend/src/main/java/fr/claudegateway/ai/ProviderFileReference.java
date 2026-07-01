package fr.claudegateway.ai;

/**
 * Référence d'un fichier tel que stocké chez le fournisseur IA, renvoyée par
 * {@link AIProvider#uploadFile(ProviderFileUpload)}. Neutre vis-à-vis du fournisseur.
 *
 * <p>L'identifiant reste interne à la Gateway (persisté en métadonnée) et n'est jamais exposé
 * tel quel au client.</p>
 *
 * @param providerFileId identifiant du fichier chez le fournisseur (ex. {@code file_...})
 */
public record ProviderFileReference(String providerFileId) {
}
