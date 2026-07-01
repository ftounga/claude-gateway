package fr.claudegateway.ai;

/**
 * Fichier à transmettre à un {@link AIProvider}. Structure neutre (nom + type + contenu), sans
 * détail spécifique à un fournisseur. Le contenu n'est jamais journalisé.
 *
 * @param filename  nom d'origine du fichier (informatif, transmis au fournisseur)
 * @param mediaType type MIME validé (ex. {@code application/pdf})
 * @param content   octets bruts du fichier à relayer
 */
public record ProviderFileUpload(String filename, String mediaType, byte[] content) {
}
