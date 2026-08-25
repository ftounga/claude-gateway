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

    /**
     * Liste les fichiers d'une branche (F-31 / SF-31-03), pour l'arborescence de l'explorateur.
     *
     * <p>Demander la liste à l'agent coûterait du temps de bac à sable facturé à chaque ouverture
     * d'écran, et exigerait une session ouverte pour un simple affichage : l'API du dépôt répond sans
     * coût runtime.</p>
     *
     * @param token      jeton en clair (jamais journalisé)
     * @param owner      propriétaire du dépôt
     * @param repo       nom du dépôt
     * @param ref        branche (ou révision) à lister
     * @param maxEntries plafond du nombre de chemins renvoyés ; au-delà la liste est tronquée et
     *                   {@link GitTreeListing#truncated()} passe à vrai
     * @return les chemins des fichiers, et l'indication de troncage
     * @throws InvalidGitRepositoryException dépôt ou branche introuvable / hors de portée du jeton
     * @throws InvalidGitTokenException      jeton refusé
     * @throws GitHubUnavailableException    GitHub injoignable
     */
    GitTreeListing listTree(String token, String owner, String repo, String ref, int maxEntries);

    /**
     * Lit le contenu texte d'un fichier d'une branche (F-31 / SF-31-03).
     *
     * @param token    jeton en clair (jamais journalisé)
     * @param owner    propriétaire du dépôt
     * @param repo     nom du dépôt
     * @param ref      branche (ou révision)
     * @param path     chemin du fichier, relatif à la racine du dépôt
     * @param maxBytes taille maximale acceptée ; au-delà, refus explicite plutôt qu'un contenu
     *                 tronqué servi comme s'il était complet
     * @return le contenu texte du fichier
     * @throws InvalidGitRepositoryException fichier introuvable sur cette branche
     * @throws GitFileNotReadableException   fichier binaire ou trop volumineux
     * @throws InvalidGitTokenException      jeton refusé
     * @throws GitHubUnavailableException    GitHub injoignable
     */
    String readFile(String token, String owner, String repo, String ref, String path, long maxBytes);

    /**
     * Constate l'existence d'une branche (F-31 / SF-31-04), après une demande de publication.
     *
     * <p>Ce que l'agent déclare ne suffit pas : il peut répondre « poussé » sans l'avoir fait — un
     * jeton en lecture seule, par exemple, échoue au {@code push} et la sortie peut passer inaperçue.
     * L'existence de la branche est donc <b>constatée</b> avant d'annoncer quoi que ce soit, et c'est
     * aussi le premier retour réel sur les droits du jeton.</p>
     *
     * @param token  jeton en clair (jamais journalisé)
     * @param owner  propriétaire du dépôt
     * @param repo   nom du dépôt
     * @param branch branche à constater
     * @return vrai si la branche existe
     * @throws InvalidGitTokenException   jeton refusé
     * @throws GitHubUnavailableException GitHub injoignable — on ne prétend alors <b>pas</b> que la
     *                                    branche existe
     */
    boolean branchExists(String token, String owner, String repo, String branch);
}
