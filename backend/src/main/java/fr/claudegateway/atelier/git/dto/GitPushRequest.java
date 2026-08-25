package fr.claudegateway.atelier.git.dto;

import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /api/workspaces/{id}/git/push} (F-31 / SF-31-04) : publier le travail de la
 * session sur une branche dédiée.
 *
 * <p>Les deux champs sont facultatifs : sans nom de branche, un nom horodaté est proposé ; sans
 * message, un libellé par défaut est utilisé. Aucun secret ne transite — le jeton reste côté serveur,
 * chiffré, et n'entre jamais dans la sandbox (proxy git, ADR-015).</p>
 *
 * @param branch  branche dédiée à créer ou mettre à jour ; jamais la branche de base
 * @param message message de commit
 */
public record GitPushRequest(
        @Size(max = 255, message = "Nom de branche trop long.")
        String branch,

        @Size(max = 500, message = "Message de commit trop long.")
        String message) {
}
