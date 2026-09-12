package fr.claudegateway.teams;

/**
 * Accès refusé au <b>volet Teams</b> (F-89 / SF-89-01) : l'utilisateur courant n'est ni {@code ADMIN}
 * ni détenteur du <b>droit Teams</b> — option Teams en cours sur un plan en cours, ou accès offert
 * (F-62). Mappée en <b>403</b> par le {@code GlobalExceptionHandler}.
 *
 * <p><b>Où cette exception apparaît, et où elle n'apparaît jamais.</b> Elle refuse l'<i>ouverture</i>
 * du terminal Teams : sans l'option, ce terminal n'existe pas. Elle n'apparaît <b>jamais</b> dans une
 * conversation : la garde des outils est au niveau du catalogue ({@code TeamsToolCatalog}), et sans
 * le droit l'agent ne <b>refuse</b> pas — il n'a pas la capacité. Il ne dira donc jamais « je
 * pourrais mais vous n'avez pas payé ».</p>
 *
 * <p>Le message ne cite <b>aucun montant</b> : le prix de l'option est à confirmer par le PO.</p>
 */
public class TeamsAccessDeniedException extends RuntimeException {

    public TeamsAccessDeniedException() {
        super("Le volet Teams demande l'option Teams ajoutée à votre offre.");
    }
}
