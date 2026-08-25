package fr.claudegateway.git;

/**
 * Dépôt GitHub tel que vu par le jeton de l'utilisateur (F-31 / SF-31-02).
 *
 * <p>Seules des informations non secrètes en sortent : le nom complet du dépôt et sa branche par
 * défaut. La branche par défaut est retenue pour deux raisons — c'est celle qui est montée quand
 * l'utilisateur n'en choisit pas, et c'est celle sur laquelle un push doit <b>toujours</b> être
 * refusé (ADR-015).</p>
 *
 * @param fullName      {@code owner/repo}
 * @param defaultBranch branche par défaut du dépôt
 */
public record GitHubRepository(String fullName, String defaultBranch) {
}
