package fr.claudegateway.governance.dto;

/**
 * Un fichier apporté, tel que l'admin le rédige (F-51 / SF-51-01, complété par F-96 / SF-96-01).
 *
 * @param path      chemin relatif au projet ; validé et normalisé côté service
 * @param kind      {@code SKILL}, {@code TEMPLATE} ou {@code MAP}
 * @param content   contenu déposé tel quel
 * @param generated vrai si le produit revendique ce fichier comme un <b>artefact généré</b> : il
 *                  sera <b>mis à jour</b> sur les postes où il est resté intact. {@code null} vaut
 *                  <b>vrai</b> — un paquet publie des artefacts, et un fichier touché par
 *                  l'utilisateur est de toute façon conservé
 */
public record GovernancePackageFileRequest(String path, String kind, String content,
        Boolean generated) {
}
