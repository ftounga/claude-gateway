package fr.claudegateway.governance.dto;

/**
 * Un fichier apporté, tel que l'admin le rédige (F-51 / SF-51-01).
 *
 * @param path    chemin relatif au projet ; validé et normalisé côté service
 * @param kind    {@code SKILL} ou {@code TEMPLATE}
 * @param content contenu déposé tel quel
 */
public record GovernancePackageFileRequest(String path, String kind, String content) {
}
