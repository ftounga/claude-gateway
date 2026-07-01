package fr.claudegateway.export;

/**
 * Fichier d'export prêt à être renvoyé au client (F-14).
 *
 * @param filename    nom de fichier proposé (en-tête {@code Content-Disposition})
 * @param contentType type MIME (en-tête {@code Content-Type})
 * @param content     octets du fichier
 */
public record ExportedFile(String filename, String contentType, byte[] content) {
}
