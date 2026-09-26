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

    /**
     * <b>Branchée, et rien à mesurer</b> (F-161 / SF-161-05) : la capacité <b>ne laisse aucune
     * trace par construction</b> — sa réussite est un événement qui <b>n'a pas lieu</b> (un tour
     * que la porte n'ouvre pas, des jetons non dépensés) ou dont l'absence est une <b>bonne
     * nouvelle</b> (aucune rupture à consigner). Son témoin de branchement, lui, est présent.
     *
     * <p><b>Ce n'est pas un constat à traiter</b> : elle est comptée avec ce qui est en ordre, pas
     * listée. Sans ce verdict il aurait fallu choisir entre deux mensonges — la dire « dormante »
     * alors qu'elle tourne, ou lui inventer un signal qu'elle ne peut pas émettre.</p>
     */
    BRANCHEE,

    /** On n'a pas pu conclure. On le dit. */
    INDETERMINEE
}
