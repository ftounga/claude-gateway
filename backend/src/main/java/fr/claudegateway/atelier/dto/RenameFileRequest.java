package fr.claudegateway.atelier.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps de {@code POST /workspaces/{id}/file/rename} : renomme (déplace) un fichier du workspace.
 * Les deux chemins sont relatifs au workspace et validés côté service (zip-slip / {@code ..} / chemin
 * absolu refusés).
 *
 * @param from chemin actuel du fichier (non vide)
 * @param to   nouveau chemin du fichier (non vide)
 */
public record RenameFileRequest(@NotBlank String from, @NotBlank String to) {
}
