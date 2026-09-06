package fr.claudegateway.atelier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /workspaces/local} (F-38 / SF-38-15) : <b>un nom, et rien d'autre</b>.
 *
 * <p>Aucun chemin n'est demandé, et ce n'est pas un oubli : un navigateur ne peut pas transmettre un
 * chemin du disque, et la gateway n'a aucune raison de connaître l'arborescence de la machine. Le
 * dossier se désigne au lancement du runner.</p>
 */
public record CreateLocalWorkspaceRequest(
        @NotBlank @Size(max = 255) String name) {
}
