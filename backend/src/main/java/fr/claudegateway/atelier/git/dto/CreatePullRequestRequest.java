package fr.claudegateway.atelier.git.dto;

import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /api/workspaces/{id}/git/pull-request} (F-31 / SF-31-05) : ouvrir la pull
 * request de la branche publiée vers la branche de base du dépôt.
 *
 * <p>La branche est <b>obligatoire</b> : elle désigne le travail que l'utilisateur vient de publier.
 * La deviner (« la dernière branche poussée ») ouvrirait la mauvaise pull request le jour où
 * l'utilisateur en a publié deux.</p>
 *
 * <p>Titre et corps sont facultatifs, avec des valeurs par défaut. Aucun secret ne transite : le
 * jeton reste côté serveur, chiffré, et sa copie de travail vit dans le vault du fournisseur.</p>
 *
 * @param branch branche de tête (celle publiée par {@code /git/push})
 * @param title  titre de la pull request ; vide ⇒ titre par défaut dérivé de la branche
 * @param body   description de la pull request ; vide ⇒ corps par défaut
 */
public record CreatePullRequestRequest(
        @Size(max = 255, message = "Nom de branche trop long.")
        String branch,

        @Size(max = 200, message = "Titre de pull request trop long.")
        String title,

        @Size(max = 4000, message = "Description de pull request trop longue.")
        String body) {
}
