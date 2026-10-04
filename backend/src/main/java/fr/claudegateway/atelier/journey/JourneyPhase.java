package fr.claudegateway.atelier.journey;

/**
 * <b>Les phases d'un sujet guidé</b> (F-176, cadrage §4) :
 * Investigation → Plan → Exécution → Vérification → Clos. Une découverte qui contredit le diagnostic
 * renvoie en Investigation ; le plan déjà validé est gardé.
 */
public enum JourneyPhase {
    INVESTIGATION("Investigation"),
    PLAN("Plan"),
    EXECUTION("Exécution"),
    VERIFICATION("Vérification"),
    CLOS("Clos");

    private final String label;

    JourneyPhase(String label) {
        this.label = label;
    }

    /** Le libellé lu par l'utilisateur et par l'agent. */
    public String label() {
        return label;
    }
}
