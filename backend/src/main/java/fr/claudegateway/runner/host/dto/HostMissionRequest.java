package fr.claudegateway.runner.host.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
 *
 * <p><b>Tolérant aux champs inconnus (F-81 / SF-81-02).</b> Règle uniforme sur tout le paquet du
 * canal runner : un corps de requête ignore ce qu'il ne connaît pas plutôt que de refuser la
 * demande entière. Aucune exception, parce qu'une exception aurait demandé une liste d'exceptions —
 * et c'est une liste tenue à la main qui a laissé {@code StoredToken} être le seul DTO strict du
 * runner, jusqu'à la panne d'appairage du 2026-09-10.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HostMissionRequest(@NotNull HostMissionStatus missionStatus) {
}
