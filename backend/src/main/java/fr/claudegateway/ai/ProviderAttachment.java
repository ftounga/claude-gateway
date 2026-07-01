package fr.claudegateway.ai;

/**
 * Référence d'un fichier déjà transmis au fournisseur, à rattacher au message utilisateur courant
 * d'une complétion (F-04). Neutre vis-à-vis du fournisseur.
 *
 * @param providerFileId identifiant du fichier chez le fournisseur
 * @param mediaType      type MIME (détermine le type de bloc : {@code image} si {@code image/*}, sinon {@code document})
 */
public record ProviderAttachment(String providerFileId, String mediaType) {
}
