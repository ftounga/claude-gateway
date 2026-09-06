package fr.claudegateway.atelier;

/**
 * Moteur qui anime le terminal d'un projet (F-39 / SF-39-07, décisions D1 et D-L4-2).
 *
 * <p>L'utilisateur ne choisit jamais cette valeur : elle est <b>résolue</b> par la gateway à partir
 * de la cible d'exécution du projet ({@link WorkspaceExecutionTarget}). Les noms disent
 * <b>où le code s'exécute</b> — la seule chose que l'utilisateur ait à comprendre — là où les
 * anciens libellés d'écran nommaient un geste (« Assistant ») et une phase (« Terminal »), ce qui
 * est précisément la confusion que F-39 corrige.</p>
 */
public enum AtelierEngine {

    /** Boucle maison, outils relayés vers la machine de l'utilisateur par son runner. */
    LOCAL_MACHINE,

    /** Managed Agents : bac à sable hébergé chez le fournisseur, aucune installation requise. */
    HOSTED_SANDBOX
}
