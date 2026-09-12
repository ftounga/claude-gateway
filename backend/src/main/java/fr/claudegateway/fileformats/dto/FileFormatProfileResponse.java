package fr.claudegateway.fileformats.dto;

import java.util.List;

/**
 * Contraintes d'un chemin de dépôt, telles que la validation les applique (F-85 / SF-85-01).
 *
 * @param mediaTypes types MIME acceptés, en minuscules, sans doublon, dans l'ordre de la
 *                   configuration (l'ordre compte : l'écran s'en sert pour nommer les formats)
 * @param maxBytes   taille maximale d'un fichier, en octets
 */
public record FileFormatProfileResponse(List<String> mediaTypes, long maxBytes) {
}
