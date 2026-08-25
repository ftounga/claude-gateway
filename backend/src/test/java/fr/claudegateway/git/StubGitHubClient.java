package fr.claudegateway.git;

/**
 * Stub de {@link GitHubClient} pour les tests d'intégration (F-31) : aucun appel réseau. Chaque
 * comportement d'échec est pilotable indépendamment, parce que le produit les distingue — un jeton
 * refusé et une panne GitHub n'ont ni le même statut HTTP ni la même action corrective.
 *
 * <p>Partagé par les tests du jeton (SF-31-01) et ceux du workspace Git (SF-31-02) : dupliquer le
 * stub ferait diverger silencieusement le contrat simulé de l'un et de l'autre.</p>
 */
public class StubGitHubClient implements GitHubClient {

    /** Simule un jeton refusé par GitHub (401/403). */
    public volatile boolean reject;

    /** Simule une panne GitHub (5xx, réseau). */
    public volatile boolean unavailable;

    /** Simule un dépôt introuvable ou hors de portée du jeton (404). */
    public volatile boolean repositoryMissing;

    /** Branche par défaut renvoyée pour tout dépôt résolu. */
    public volatile String defaultBranch = "main";

    /** Dernier jeton reçu : sert à vérifier que c'est bien celui du propriétaire qui est utilisé. */
    public volatile String lastToken;

    /** Dernier dépôt demandé, sous la forme {@code owner/repo}. */
    public volatile String lastRepository;

    public void reset() {
        reject = false;
        unavailable = false;
        repositoryMissing = false;
        defaultBranch = "main";
        lastToken = null;
        lastRepository = null;
    }

    @Override
    public GitHubAccount verifyToken(String token) {
        this.lastToken = token;
        failIfSimulated();
        return new GitHubAccount("octocat");
    }

    @Override
    public GitHubRepository getRepository(String token, String owner, String repo) {
        this.lastToken = token;
        this.lastRepository = owner + "/" + repo;
        failIfSimulated();
        if (repositoryMissing) {
            throw new InvalidGitRepositoryException("dépôt introuvable (simulé)");
        }
        return new GitHubRepository(owner + "/" + repo, defaultBranch);
    }

    private void failIfSimulated() {
        if (unavailable) {
            throw new GitHubUnavailableException("panne simulée");
        }
        if (reject) {
            throw new InvalidGitTokenException("jeton refusé");
        }
    }
}
