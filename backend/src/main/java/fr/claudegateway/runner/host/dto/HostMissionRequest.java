package fr.claudegateway.runner.host.dto;

import fr.claudegateway.runner.host.HostMissionStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Corps de {@code PUT /runner-hosts/{hostId}/mission} (F-60 / SF-60-01) : l'état de mission
 * <b>déclaré</b> par le propriétaire du poste.
 *
 * <p><b>Énumération stricte</b> : la valeur vient d'un client, elle est acceptée telle qu'elle est
 * écrite au contrat ou refusée en 400. Aucune tolérance à la casse — {@code "active"} n'est pas
 * {@code ACTIVE}. Une énumération de contrat n'est pas un texte libre, et la tolérance est une
 * dette qui finit par accepter des valeurs qu'on n'a jamais voulues.</p>
 */
public record HostMissionRequest(@NotNull HostMissionStatus missionStatus) {
}
