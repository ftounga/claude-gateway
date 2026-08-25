package fr.claudegateway.git;

/**
 * Vérifie un jeton d'accès GitHub auprès de GitHub (F-31 / SF-31-01).
 *
 * <p>Interface volontairement minimale : elle isole le seul point du code qui parle à GitHub, ce qui
 * rend le service testable sans réseau et laisse la porte ouverte à une authentification GitHub App
 * (D2 option B du cadrage) sans réécriture du domaine.</p>
 *
 * <p>Le jeton ne transite qu'en argument : il n'est ni journalisé, ni conservé, ni renvoyé.</p>
 */
public interface GitHubClient {

    /**
     * Vérifie que le jeton est accepté par GitHub et renvoie le compte auquel il donne accès.
     *
     * @param token jeton en clair (jamais journalisé)
     * @return le compte GitHub associé
     * @throws InvalidGitTokenException   si GitHub refuse le jeton (invalide, révoqué, expiré)
     * @throws GitHubUnavailableException si GitHub est injoignable ou répond en erreur serveur
     */
    GitHubAccount verifyToken(String token);
}
