package fr.claudegateway.runner.channel;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Une trame de <b>diagnostic du runner</b> (F-132 / SF-132-02) : {@code runner_diag} (SF-132-01),
 * portant un lot d'événements structurés et expurgés.
 *
 * <p>Publiée par {@link RunnerCallDispatcher} et consommée par {@code RunnerDiagService}, pour que le
 * protocole runner ne dépende pas du service de persistance (même schéma que
 * {@link RunnerUpdateFrameEvent}).</p>
 *
 * @param userId propriétaire du poste, issu de la <b>session</b> runner — jamais un champ de la trame
 * @param hostId poste de la <b>session</b> runner — jamais un champ de la trame
 * @param frame  la trame {@code runner_diag} reçue
 */
public record RunnerDiagFrameEvent(UUID userId, UUID hostId, JsonNode frame) {
}
