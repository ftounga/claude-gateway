package fr.claudegateway.atelier;

/**
 * Cible d'exécution des outils d'un workspace d'Atelier (F-38 / SF-38-05, décision D1). Symétrique de
 * la {@link WorkspaceSource} : la source dit <b>d'où viennent</b> les fichiers, la cible dit
 * <b>où s'exécutent</b> les outils. Les deux dimensions sont indépendantes — un dépôt Git cloné sur
 * la machine de l'utilisateur est un couple {@code GIT} + {@code RUNNER} parfaitement légitime.
 */
public enum WorkspaceExecutionTarget {

    /**
     * Comportement historique : les outils fichiers s'exécutent côté gateway sur le stockage objet
     * (mode Assistant), et le mode Terminal ouvre un bac à sable chez le fournisseur.
     */
    SANDBOX,

    /**
     * Les outils s'exécutent sur la machine de l'utilisateur, relayés par le canal WebSocket runner
     * (SF-38-02/03/04). La gateway ne stocke plus les fichiers : elle relaie. Les Managed Agents ne
     * sont pas utilisables dans ce mode (décision D2 : ils exécutent les outils chez le fournisseur).
     */
    RUNNER
}
