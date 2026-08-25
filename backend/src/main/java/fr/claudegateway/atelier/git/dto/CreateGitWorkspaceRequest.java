package fr.claudegateway.atelier.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /api/workspaces/git} (F-31 / SF-31-02) : ouvrir un projet d'Atelier sur un
 * dépôt GitHub existant.
 *
 * <p>Aucun secret n'est transmis : le jeton d'accès a été enregistré séparément (SF-31-01), chiffré,
 * et il est résolu côté serveur à partir du {@code user_id} du JWT. Le client ne fournit jamais
 * d'identifiant d'utilisateur.</p>
 *
 * @param repoUrl URL du dépôt ({@code https://github.com/proprietaire/depot})
 * @param branch  branche à monter ; {@code null} ⇒ branche par défaut du dépôt
 * @param name    nom du projet dans l'Atelier ; {@code null} ⇒ nom du dépôt
 */
public record CreateGitWorkspaceRequest(
        @NotBlank(message = "L'URL du dépôt est obligatoire.")
        @Size(max = 500, message = "URL de dépôt trop longue.")
        String repoUrl,

        @Size(max = 255, message = "Nom de branche trop long.")
        String branch,

        @Size(max = 255, message = "Nom de projet trop long.")
        String name) {
}
