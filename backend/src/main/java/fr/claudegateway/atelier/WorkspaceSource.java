package fr.claudegateway.atelier;

/**
 * Provenance des fichiers d'un workspace d'Atelier (F-31 / SF-31-02, ADR-015). Le reste de l'Atelier
 * — session persistante, mode Terminal, historique, décompte d'usage — est identique quelle que soit
 * la source : seul l'approvisionnement des fichiers change.
 */
public enum WorkspaceSource {

    /**
     * Projet téléversé en archive {@code .zip} (F-28). Les fichiers vivent dans le stockage objet et
     * sont téléversés un par un chez le fournisseur à l'ouverture de session.
     */
    ARCHIVE,

    /**
     * Dépôt GitHub monté par le fournisseur ({@code github_repository}). Les fichiers ne sont pas
     * copiés dans le stockage objet : le dépôt est cloné dans la sandbox, et le jeton d'accès
     * n'entre jamais dans le conteneur (proxy git côté fournisseur, ADR-015).
     */
    GIT
}
