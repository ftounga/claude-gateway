package fr.claudegateway.runner.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Réponse à l'appairage (F-38 / SF-38-01). Le jeton en clair n'apparaît qu'ici et n'est jamais
 * réexposé par l'API.
 *
 * <p>Depuis F-48 / SF-48-01, c'est le <b>poste</b> qui est rendu, pas un projet : le runner est
 * appairé à une machine, et les projets sont des sous-dossiers de sa racine.</p>
 *
 * <p>F-194 / SF-194-03 : champs <b>additifs</b> {@code hostName} (le poste auquel le code a rattaché
 * la machine) et {@code previousRootName} (racine que cette machine remplace, sinon {@code null}).
 * Un runner antérieur les ignore.</p>
 */
public record PairResponse(String token, UUID hostId, OffsetDateTime expiresAt, String hostName,
        String previousRootName) {
}
