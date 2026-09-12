package fr.claudegateway.docx;

/**
 * Résultat de la lecture d'un {@code .docx} (F-86 / SF-86-01).
 *
 * @param text          le texte du document, dans son ordre ; tableaux rendus en Markdown, notes de
 *                      bas de page en fin de texte, en-têtes et pieds de page absents
 * @param ignoredImages nombre d'images que le document contenait et que l'extraction n'a pas lues.
 *                      Le compte n'est pas décoratif : {@link #text()} l'<b>annonce</b> en tête,
 *                      pour qu'un document amputé de ses images ne passe pas pour un document
 *                      complet
 */
public record DocxExtraction(String text, int ignoredImages) {
}
