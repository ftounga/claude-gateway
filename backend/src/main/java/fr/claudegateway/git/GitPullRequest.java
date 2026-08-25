package fr.claudegateway.git;

/**
 * Pull request <b>constatée</b> auprès de GitHub (F-31 / SF-31-05).
 *
 * <p>Ce record n'existe que lorsque GitHub a confirmé l'existence de la PR : rien ici n'est déduit
 * de ce que l'agent a répondu. Un agent peut annoncer « pull request créée » sans l'avoir fait —
 * jeton sans droit d'écriture, outil MCP indisponible, PR déjà ouverte.</p>
 *
 * @param number numéro de la pull request
 * @param url    URL publique de la pull request ({@code html_url})
 */
public record GitPullRequest(int number, String url) {
}
