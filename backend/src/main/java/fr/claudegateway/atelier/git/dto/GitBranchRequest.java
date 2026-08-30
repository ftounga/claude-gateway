package fr.claudegateway.atelier.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps des endpoints de branche (F-31 / SF-31-10) : celle sur laquelle se placer, ou celle à créer.
 *
 * @param branch nom de branche, validé plus finement côté service ({@code GitRepositoryRef})
 */
public record GitBranchRequest(
        @NotBlank(message = "Le nom de la branche est requis.")
        @Size(max = 255, message = "Nom de branche trop long.")
        String branch) {
}
