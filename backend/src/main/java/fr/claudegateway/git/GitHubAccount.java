package fr.claudegateway.git;

/**
 * Compte GitHub auquel un jeton donne accès, tel que renvoyé par la vérification. Ne porte que de
 * l'information publique — jamais le jeton.
 *
 * @param login nom de compte GitHub (peut être {@code null} si GitHub ne le renvoie pas)
 */
public record GitHubAccount(String login) {
}
