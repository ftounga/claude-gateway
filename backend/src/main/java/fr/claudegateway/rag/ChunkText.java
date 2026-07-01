package fr.claudegateway.rag;

/**
 * Fragment de texte produit par le {@link Chunker}, avant vectorisation/persistance (F-06).
 *
 * @param index     position ordinale (0..N-1)
 * @param text      contenu du fragment
 * @param charStart offset de début dans le texte source (inclus)
 * @param charEnd   offset de fin dans le texte source (exclu)
 */
public record ChunkText(int index, String text, int charStart, int charEnd) {
}
