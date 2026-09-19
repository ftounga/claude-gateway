package fr.claudegateway.runner.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.diag.RunnerDiagEventEntity;

/**
 * Un événement de diagnostic du runner tel qu'il est rendu au propriétaire du poste
 * (F-132 / SF-132-02) : une <b>forme</b> et un <b>état</b>, jamais un contenu. Déjà expurgé à la
 * source (SF-132-01).
 *
 * @param id         identifiant de la ligne
 * @param level      {@code DEBUG}/{@code INFO}/{@code WARN}/{@code ERROR}
 * @param category   catégorie courte (chrome/teams/capture/vigie/error)
 * @param code       code court de l'événement
 * @param message    message court expurgé, ou {@code null}
 * @param fields     petite carte de champs scalaires expurgés (objet JSON), ou {@code null}
 * @param observedAt horodatage d'observation côté runner, ou {@code null}
 * @param createdAt  horodatage serveur de réception
 */
public record RunnerDiagResponse(
        UUID id,
        String level,
        String category,
        String code,
        String message,
        JsonNode fields,
        OffsetDateTime observedAt,
        OffsetDateTime createdAt) {

    public static RunnerDiagResponse from(RunnerDiagEventEntity e, ObjectMapper mapper) {
        return new RunnerDiagResponse(e.getId(), e.getLevel(), e.getCategory(), e.getCode(),
                e.getMessage(), parseFields(e.getFields(), mapper), e.getObservedAt(),
                e.getCreatedAt());
    }

    private static JsonNode parseFields(String fields, ObjectMapper mapper) {
        if (fields == null || fields.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(fields);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // Un JSON stocké illisible ne doit jamais casser la lecture du journal : on rend null.
            return null;
        }
    }
}
