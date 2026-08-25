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

    /**
     * Résout un dépôt visible par ce jeton (F-31 / SF-31-02) : confirme l'accès et renvoie sa branche
     * par défaut, qui sert de révision montée quand l'utilisateur n'en choisit pas, et de branche
     * interdite au push.
     *
     * @param token jeton en clair (jamais journalisé)
     * @param owner propriétaire du dépôt
     * @param repo  nom du dépôt
     * @return le dépôt et sa branche par défaut
     * @throws InvalidGitRepositoryException si le dépôt est introuvable ou hors de portée du jeton
     * @throws InvalidGitTokenException      si GitHub refuse le jeton
     * @throws GitHubUnavailableException    si GitHub est injoignable ou répond en erreur serveur
     */
    GitHubRepository getRepository(String token, String owner, String repo);
}
