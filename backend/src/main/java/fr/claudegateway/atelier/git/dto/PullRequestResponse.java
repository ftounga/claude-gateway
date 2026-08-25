package fr.claudegateway.atelier.git.dto;

/**
 * Résultat d'une demande d'ouverture de pull request (F-31 / SF-31-05).
 *
 * <p>{@code created} est <b>constaté auprès de GitHub</b>, jamais déduit de ce que l'agent répond :
 * il peut annoncer « pull request créée » sans l'avoir fait — jeton sans droit d'écriture, outil MCP
 * indisponible, pull request déjà ouverte sur cette branche. Fabriquer une URL depuis sa réponse
 * mènerait l'utilisateur vers une page inexistante, ou vers la pull request de quelqu'un d'autre.</p>
 *
 * <p>Un échec reste un {@code 200} : le tour a eu lieu et a été facturé, et son compte rendu
 * ({@code reply}) est l'information utile. Une 5xx le masquerait.</p>
 *
 * @param branch  branche de tête visée
 * @param created vrai si une pull request ouverte existe réellement sur cette branche après le tour
 * @param url     URL publique de la pull request, {@code null} si aucune n'a été constatée
 * @param number  numéro de la pull request, {@code null} si aucune n'a été constatée
 * @param reply   compte rendu de l'agent — c'est là que se lit la cause d'un échec
 */
public record PullRequestResponse(String branch, boolean created, String url, Integer number,
        String reply) {
}
