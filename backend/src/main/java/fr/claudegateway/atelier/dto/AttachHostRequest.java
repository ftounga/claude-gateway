package fr.claudegateway.atelier.dto;

import java.util.UUID;

import jakarta.validation.constraints.Size;

/**
 * Corps de {@code PUT /workspaces/{id}/host} (F-48 / SF-48-01) : <b>rattacher</b> un projet à un
 * poste, et dire où il vit sous sa racine.
 *
 * <p>C'est le geste qui remplace un appairage. Le poste est appairé une fois ; ouvrir un projet de
 * plus sous sa racine ne coûte plus que cette requête.</p>
 *
 * @param hostId      poste visé, ou {@code null} pour <b>détacher</b> le projet
 * @param projectPath chemin relatif du projet sous la racine du poste, séparateur {@code /}.
 *                    {@code null} ou vide désigne la racine elle-même — un poste peut n'héberger
 *                    qu'un projet
 */
public record AttachHostRequest(UUID hostId, @Size(max = 512) String projectPath) {
}
