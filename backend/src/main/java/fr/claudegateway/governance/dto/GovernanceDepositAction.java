package fr.claudegateway.governance.dto;

/**
 * Ce qui va arriver — ou ce qui est arrivé — à un fichier apporté par un paquet (F-51 / SF-51-03).
 *
 * <p>C'est le vocabulaire de l'annonce faite <b>avant</b> l'activation, et il n'en compte que trois
 * mots parce que le produit ne s'autorise que trois conduites.</p>
 */
public enum GovernanceDepositAction {

    /** Le fichier n'existe pas dans le projet : il sera créé. */
    CREATE,

    /**
     * Le fichier existe déjà : il sera <b>laissé tel quel</b>, contenu différent compris.
     *
     * <p>C'est la promesse d'idempotence de la feature, et la seule qui protège le travail de
     * l'utilisateur : écraser un {@code STATE.md} rempli parce qu'un paquet en apporte un vide serait
     * une perte de données déclenchée par une case cochée.</p>
     */
    KEEP,

    /**
     * Le projet n'a pas pu être lu — machine éteinte, refus, stockage indisponible.
     *
     * <p>On ne <b>prétend</b> pas : dire « sera créé » sans avoir pu regarder reviendrait à annoncer
     * une écriture qu'on n'est pas sûr de faire.</p>
     */
    UNKNOWN
}
