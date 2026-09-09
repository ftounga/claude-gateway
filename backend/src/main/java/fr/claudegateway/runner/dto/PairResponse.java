package fr.claudegateway.runner.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Réponse à l'appairage (F-38 / SF-38-01). Le jeton en clair n'apparaît qu'ici et n'est jamais
 * réexposé par l'API.
 *
 * <p>Depuis F-48 / SF-48-01, c'est le <b>poste</b> qui est rendu, pas un projet : le runner est
 * appairé à une machine, et les projets sont des sous-dossiers de sa racine.</p>
 */
public record PairResponse(String token, UUID hostId, OffsetDateTime expiresAt) {
}
