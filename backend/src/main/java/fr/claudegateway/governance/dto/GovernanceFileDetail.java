package fr.claudegateway.governance.dto;

/**
 * Un fichier apporté, avec son contenu (F-51 / SF-51-01). Réservé à l'<b>admin</b>, qui rédige.
 *
 * @param path      chemin relatif au projet
 * @param kind      {@code SKILL}, {@code TEMPLATE} ou {@code MAP}
 * @param content   contenu déposé tel quel
 * @param generated vrai si le paquet revendique ce fichier comme un <b>artefact généré</b>, et donc
 *                  le met à jour là où il est resté intact (F-96 / SF-96-01)
 */
public record GovernanceFileDetail(String path, String kind, String content, boolean generated) {
}
