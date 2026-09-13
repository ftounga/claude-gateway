package fr.claudegateway.governance.dto;

/**
 * Ce qui va arriver — ou ce qui est arrivé — à un fichier apporté par un paquet (F-51 / SF-51-03,
 * <b>complété par F-96 / SF-96-01</b>).
 *
 * <p>C'est le vocabulaire de l'annonce faite <b>avant</b> l'activation. Il en comptait trois mots
 * parce que le produit ne s'autorisait que trois conduites ; il en compte cinq depuis que la
 * gouvernance sait <b>se mettre à jour</b> — et les deux mots ajoutés sont exactement les deux
 * choses qu'on ne pouvait pas dire.</p>
 */
public enum GovernanceDepositAction {

    /** Le fichier n'existe pas à cet endroit : il sera créé. */
    CREATE,

    /**
     * Le fichier est un <b>artefact généré</b>, resté <b>exactement</b> celui qu'on avait déposé, et
     * le paquet en apporte une version différente : il sera <b>remplacé</b> (F-96 / SF-96-01).
     *
     * <p>C'est la troisième issue qui manquait. Sans elle, un skill corrigé n'atteint jamais un
     * poste qui a déjà l'ancienne version : un client reste sur la gouvernance du jour de son
     * activation, pour toujours, et le produit publie un catalogue qu'il ne peut pas faire
     * évoluer.</p>
     *
     * <p><b>Elle n'arrive jamais toute seule</b> : seul un geste explicite de l'utilisateur
     * (« activer », « appliquer ») peut la déclencher. Les dépôts automatiques — un dossier créé
     * hérite de son poste — ne créent que ce qui manque.</p>
     */
    UPDATE,

    /**
     * Le fichier reste <b>tel quel</b> parce qu'il n'y a rien à y faire : il est déjà exactement ce
     * que le paquet apporte, ou le paquet ne le déclare pas comme un artefact qu'il met à jour.
     *
     * <p>C'est la promesse d'idempotence de la feature : republier un paquet ne réécrit rien chez
     * personne.</p>
     */
    KEEP,

    /**
     * Le fichier est conservé parce qu'il a été <b>modifié localement</b> (F-96 / SF-96-01).
     *
     * <p><b>Ce n'est pas la même chose que {@link #KEEP}</b>, et c'est tout l'intérêt de le dire :
     * un fichier conservé parce qu'il a été modifié n'est pas un fichier conservé parce qu'il était
     * déjà bon. Sans la distinction, on ne sait jamais si sa correction est arrivée.</p>
     *
     * <p>Un fichier que l'utilisateur a touché <b>redevient du contenu utilisateur</b> : on ne le
     * touche plus, jamais, et il n'existe aucun geste « forcer ». Un fichier dont on ne sait pas
     * d'où il vient est traité de la même façon — le doute n'écrit pas.</p>
     */
    KEEP_LOCAL,

    /**
     * Le projet n'a pas pu être lu — machine éteinte, refus, stockage indisponible.
     *
     * <p>On ne <b>prétend</b> pas : dire « sera créé » sans avoir pu regarder reviendrait à annoncer
     * une écriture qu'on n'est pas sûr de faire.</p>
     */
    UNKNOWN
}
