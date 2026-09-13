package fr.claudegateway.governance;

/**
 * Jusqu'où un dépôt a le droit d'aller (F-96 / SF-96-01).
 *
 * <p><b>Le geste appartient à l'utilisateur.</b> Rien ne se met à jour tout seul : le produit
 * n'écrit <b>jamais</b> par-dessus un fichier de la machine d'un client sans qu'on le lui ait
 * demandé. Or tous les dépôts ne viennent pas d'un geste — un dossier créé hérite de la gouvernance
 * de son poste, en silence, sans que personne n'ait cliqué. Ce mode est ce qui sépare les deux, et
 * il est passé <b>par l'appelant</b> plutôt que deviné : un appel automatique qui oublierait de le
 * dire écrirait sans qu'on l'ait demandé, et c'est exactement ce qu'il ne faut pas.</p>
 */
public enum GovernanceDepositMode {

    /**
     * Geste explicite de l'utilisateur — « activer » après avoir lu l'annonce, ou « appliquer ».
     * Les cinq issues sont possibles, <b>mise à jour comprise</b>.
     */
    FULL,

    /**
     * Dépôt <b>automatique</b> : un dossier neuf hérite des paquets déjà actifs sur son poste.
     *
     * <p>Seul ce qui <b>manque</b> est créé. Un fichier déjà présent est laissé tel quel — même s'il
     * est un artefact périmé : un dossier créé sur un répertoire existant ne doit pas voir ses
     * fichiers réécrits dans son dos, et l'écran offrira « appliquer ».</p>
     */
    CREATE_ONLY
}
