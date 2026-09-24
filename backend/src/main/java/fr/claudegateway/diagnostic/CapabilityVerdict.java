package fr.claudegateway.diagnostic;

/**
 * Ce que le diagnostic conclut d'une capacité (F-156 / SF-156-03).
 *
 * <p>Trois issues, et la troisième compte autant que les deux autres : <b>dire qu'on ne sait pas</b>
 * vaut mieux que deviner. Un diagnostic qui annonce « dormante » sur une capacité qui tourne très
 * bien perd sa crédibilité au deuxième rapport.</p>
 */
public enum CapabilityVerdict {

    /** Le signal a été vu : la capacité tourne. */
    ACTIVE,

    /**
     * La capacité est <b>présente</b> — la garde de la carte le garantit — et ne s'est <b>pas
     * déclenchée</b>. Aucun développement à faire : un branchement à réparer.
     */
    DORMANTE,

    /** On n'a pas pu conclure. On le dit. */
    INDETERMINEE
}
