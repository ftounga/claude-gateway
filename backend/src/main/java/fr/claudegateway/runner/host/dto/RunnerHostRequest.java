package fr.claudegateway.runner.host.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de création et de renommage d'un poste (F-48 / SF-48-01). Le nom est <b>libre</b> : rien
 * n'empêche de nommer un poste du nom du client chez qui il est installé (décision n° 1 du cadrage).
 *
 * <p><b>Tolérant aux champs inconnus (F-81 / SF-81-02).</b> Règle uniforme sur tout le paquet du
 * canal runner : un corps de requête ignore ce qu'il ne connaît pas plutôt que de refuser la
 * demande entière. Aucune exception, parce qu'une exception aurait demandé une liste d'exceptions —
 * et c'est une liste tenue à la main qui a laissé {@code StoredToken} être le seul DTO strict du
 * runner, jusqu'à la panne d'appairage du 2026-09-10.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunnerHostRequest(@NotBlank @Size(max = 100) String name) {
}
