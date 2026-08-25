package fr.claudegateway.atelier.git.dto;

/**
 * Résultat d'une publication sur branche (F-31 / SF-31-04).
 *
 * <p>{@code pushed} est <b>constaté auprès de GitHub</b>, pas déduit de ce que l'agent répond : un
 * agent peut annoncer « poussé » sans l'avoir fait (jeton en lecture seule, rien à commiter, échec
 * passé inaperçu dans une longue sortie).</p>
 *
 * <p>Un échec de publication reste un {@code 200} : le tour a bien eu lieu et a été facturé, et son
 * compte rendu ({@code reply}) est l'information utile. Une 5xx le masquerait.</p>
 *
 * @param branch     branche visée
 * @param pushed     vrai si la branche existe réellement sur le dépôt après le tour
 * @param compareUrl lien d'ouverture de pull request, {@code null} si rien n'a été poussé
 * @param reply      compte rendu de l'agent — c'est là que se lit la cause d'un échec
 */
public record GitPushResponse(String branch, boolean pushed, String compareUrl, String reply) {
}
