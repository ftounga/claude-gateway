package fr.claudegateway.atelier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /workspaces/{id}/rename} : renomme le projet (F-28 / SF-28-16).
 *
 * <p>Le nom est une <b>étiquette</b> : il n'identifie rien. Les fichiers vivent sous l'identifiant du
 * workspace, pas sous son nom, et deux projets peuvent porter le même — imposer l'unicité créerait une
 * erreur là où l'utilisateur ne voit qu'un libellé.</p>
 *
 * @param name nouveau nom, non vide et borné à la longueur de la colonne
 */
public record RenameWorkspaceRequest(@NotBlank @Size(max = 255) String name) {
}
