package fr.claudegateway.governance.integrite;

/**
 * Les <b>deux niveaux</b> d'un constat d'intégrité (F-95 / SF-95-01).
 *
 * <p>C'est une exigence littérale de la feature — « deux niveaux distincts : erreurs (bloquantes) et
 * avertissements (informatifs). Ne les mélange pas. » — et elle n'est pas décorative : un contrôle
 * qui bloquerait sur tout finirait débranché, et un contrôle qui n'alerterait sur rien ne servirait
 * à rien.</p>
 *
 * <p><b>Où passe la frontière</b>, posée une fois pour toutes : est une <b>erreur</b> ce qu'un
 * modèle peut corriger <i>mécaniquement</i> et dont l'absence de correction <b>fait perdre du
 * savoir</b> — plus de destination où promouvoir, plus de trace de dette, une note qui part chez un
 * client. Est un <b>avertissement</b> ce qui demande un <i>jugement</i> : le corriger sans le
 * jugement reviendrait à deviner, et deviner dans la carte d'un client est pire que la laisser
 * vieillir.</p>
 */
public enum IntegriteNiveau {

    /** La gouvernance ne peut pas fonctionner en l'état. Bloque la fin du tour (SF-95-03). */
    ERREUR("ERREURS"),

    /** La carte fonctionne, mais elle vieillit mal. <b>Ne bloque jamais.</b> */
    AVERTISSEMENT("AVERTISSEMENTS");

    private final String intitule;

    IntegriteNiveau(String intitule) {
        this.intitule = intitule;
    }

    /** L'intitulé sous lequel ce niveau est cité, en tête de son bloc — jamais mêlé à l'autre. */
    public String intitule() {
        return intitule;
    }

    /** Vrai si ce niveau empêche la fin d'un tour. */
    public boolean bloquant() {
        return this == ERREUR;
    }
}
