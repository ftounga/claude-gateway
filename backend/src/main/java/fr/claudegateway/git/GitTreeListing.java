package fr.claudegateway.git;

import java.util.List;

/**
 * Arborescence d'une branche telle que renvoyée par GitHub (F-31 / SF-31-03) : les chemins des
 * fichiers, et un drapeau de troncage.
 *
 * <p>{@code truncated} est vrai lorsque la liste ne représente pas tout le dépôt — soit parce que
 * GitHub a lui-même tronqué sa réponse (dépôt très volumineux), soit parce que notre propre plafond
 * l'a coupée. Le distinguer d'une liste complète est essentiel : un explorateur silencieusement
 * partiel ferait croire qu'un fichier n'existe pas.</p>
 *
 * @param paths     chemins des fichiers (blobs), relatifs à la racine du dépôt
 * @param truncated vrai si la liste est incomplète
 */
public record GitTreeListing(List<String> paths, boolean truncated) {
}
