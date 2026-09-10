package fr.claudegateway.governance.dto;

/**
 * Un fichier apporté, avec son contenu (F-51 / SF-51-01). Réservé à l'<b>admin</b>, qui rédige.
 *
 * @param path    chemin relatif au projet
 * @param kind    {@code SKILL} ou {@code TEMPLATE}
 * @param content contenu déposé tel quel
 */
public record GovernanceFileDetail(String path, String kind, String content) {
}
