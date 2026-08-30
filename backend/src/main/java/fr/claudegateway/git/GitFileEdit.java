package fr.claudegateway.git;

/**
 * Un fichier à publier dans un commit (F-31 / SF-31-08) : son chemin relatif dans le dépôt et son
 * contenu texte complet. Le contenu remplace la version de la branche — il n'y a pas de patch.
 *
 * @param path    chemin relatif, sans {@code ..} ni racine absolue (validé en amont)
 * @param content contenu texte complet du fichier
 */
public record GitFileEdit(String path, String content) {
}
