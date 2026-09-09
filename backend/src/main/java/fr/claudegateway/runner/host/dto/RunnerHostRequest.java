package fr.claudegateway.runner.host.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de création et de renommage d'un poste (F-48 / SF-48-01). Le nom est <b>libre</b> : rien
 * n'empêche de nommer un poste du nom du client chez qui il est installé (décision n° 1 du cadrage).
 */
public record RunnerHostRequest(@NotBlank @Size(max = 100) String name) {
}
