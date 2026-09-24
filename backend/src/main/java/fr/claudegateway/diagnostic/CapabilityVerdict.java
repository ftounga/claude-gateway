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

    /**
     * <b>Débranchée</b> (F-157 / SF-157-03) : un témoin de branchement <b>manque dans le code</b>.
     * Un remaniement l'a détachée — rien n'a cassé, aucun test n'est tombé.
     *
     * <p>Distinguer ceci de {@link #DORMANTE} change le geste : devant « dormante » on cherche une
     * donnée, une condition, un amorçage ; si la capacité est en réalité débranchée, cette
     * recherche ne trouve rien, et l'on conclut que le diagnostic se trompe.</p>
     */
    DEBRANCHEE,

    /** On n'a pas pu conclure. On le dit. */
    INDETERMINEE
}
