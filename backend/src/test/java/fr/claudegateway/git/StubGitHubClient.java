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

    /** Arborescence renvoyée pour toute branche résolue (SF-31-03). */
    public volatile java.util.List<String> treePaths = java.util.List.of("README.md", "src/App.java");

    /** Vrai pour simuler une arborescence partielle (dépôt très volumineux). */
    public volatile boolean treeTruncated;

    /** Contenu renvoyé par {@link #readFile} ; {@code null} ⇒ fichier absent de la branche. */
    public volatile String fileContent = "contenu de la branche";

    /** Dernière branche demandée : sert à vérifier que c'est bien celle qui est montée. */
    public volatile String lastRef;

    /** Dernier chemin lu sur la branche. */
    public volatile String lastPath;

    /** Réponse de {@link #branchExists} : simule un push réussi (vrai) ou raté (faux) — SF-31-04. */
    public volatile boolean branchPushed = true;

    /** Dernière branche dont l'existence a été constatée. */
    public volatile String lastCheckedBranch;

    public void reset() {
        reject = false;
        unavailable = false;
        repositoryMissing = false;
        defaultBranch = "main";
        lastToken = null;
        lastRepository = null;
        treePaths = java.util.List.of("README.md", "src/App.java");
        treeTruncated = false;
        fileContent = "contenu de la branche";
        lastRef = null;
        lastPath = null;
        branchPushed = true;
        lastCheckedBranch = null;
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

    @Override
    public GitTreeListing listTree(String token, String owner, String repo, String ref, int maxEntries) {
        this.lastToken = token;
        this.lastRepository = owner + "/" + repo;
        this.lastRef = ref;
        failIfSimulated();
        if (repositoryMissing) {
            throw new InvalidGitRepositoryException("branche introuvable (simulée)");
        }
        java.util.List<String> paths = treePaths.size() > maxEntries
                ? treePaths.subList(0, maxEntries)
                : treePaths;
        return new GitTreeListing(java.util.List.copyOf(paths), treeTruncated || treePaths.size() > maxEntries);
    }

    @Override
    public String readFile(String token, String owner, String repo, String ref, String path, long maxBytes) {
        this.lastToken = token;
        this.lastRepository = owner + "/" + repo;
        this.lastRef = ref;
        this.lastPath = path;
        failIfSimulated();
        if (fileContent == null) {
            throw new InvalidGitRepositoryException("fichier introuvable sur cette branche (simulé)");
        }
        return fileContent;
    }

    @Override
    public boolean branchExists(String token, String owner, String repo, String branch) {
        this.lastToken = token;
        this.lastRepository = owner + "/" + repo;
        this.lastCheckedBranch = branch;
        failIfSimulated();
        return branchPushed;
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
