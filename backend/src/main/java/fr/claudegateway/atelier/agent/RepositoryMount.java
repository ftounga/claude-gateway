package fr.claudegateway.atelier.agent;

/**
 * Montage d'un dépôt Git dans une session Managed Agents (F-31 / SF-31-02, ADR-015). Pendant du
 * {@link FileMount}, pour un projet dont les fichiers ne viennent pas du stockage objet mais d'un
 * dépôt cloné par le fournisseur.
 *
 * <p><b>Le jeton ne traverse jamais le conteneur</b> : le proxy git du fournisseur l'injecte après
 * que la requête a quitté le sandbox. Le code exécuté dans la sandbox — y compris celui que l'agent
 * écrit — ne peut ni le lire ni l'exfiltrer.</p>
 *
 * <p>Ce record porte donc un secret en mémoire, le temps d'un appel : il ne doit jamais être
 * journalisé, ni sérialisé ailleurs que dans la requête de création de session. {@link #toString()}
 * est redéfini pour que le jeton ne fuie pas dans une trace ou un message d'erreur.</p>
 *
 * @param url                URL publique du dépôt ({@code https://github.com/owner/repo})
 * @param authorizationToken jeton d'accès en clair, destiné au seul proxy git du fournisseur
 * @param mountPath          chemin de montage dans la sandbox (p. ex. {@code /workspace})
 * @param branch             branche à extraire ({@code checkout: {type: branch, name}})
 */
public record RepositoryMount(String url, String authorizationToken, String mountPath, String branch) {

    /** Représentation sûre : le jeton est remplacé par un marqueur, jamais rendu. */
    @Override
    public String toString() {
        return "RepositoryMount[url=" + url + ", mountPath=" + mountPath + ", branch=" + branch
                + ", authorizationToken=***]";
    }
}
