package fr.claudegateway.atelier;

/**
 * Motif pour lequel le runner mérite d'être proposé sur ce projet (F-39 / SF-39-07, décision D6 du
 * cadrage).
 *
 * <p>Le runner est le chemin <b>recommandé</b>, jamais le premier pas : la proposition ne tombe que
 * lorsqu'une limite du bac à sable est <b>réellement rencontrée</b>. Il n'existe donc pas de motif
 * « générique » — chaque valeur nomme la limite constatée.</p>
 */
public enum RunnerRecommendation {

    /** Projet adossé à un dépôt Git : le clone local a tout son sens. */
    GIT,

    /** Le bac à sable ne monte pas tout le projet (arborescence tronquée, ou plafond atteint). */
    FILE_LIMIT
}
