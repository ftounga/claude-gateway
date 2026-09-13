package fr.claudegateway.runner.channel;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.runner.host.RunnerDeclaration;

/**
 * Une trame qui concerne la <b>mise à jour du runner</b> (F-111 / SF-111-04) : {@code update_status},
 * ou {@code ready} (un runner qui revient clôt une mise à jour).
 *
 * @param hostId      poste de la <b>session</b> runner — jamais un champ de la trame
 * @param type        {@code update_status} ou {@code ready}
 * @param frame       la trame reçue
 * @param declaration la déclaration lue dans {@code ready}, nulle pour {@code update_status}
 */
public record RunnerUpdateFrameEvent(UUID hostId, String type, JsonNode frame, RunnerDeclaration declaration) {
}
