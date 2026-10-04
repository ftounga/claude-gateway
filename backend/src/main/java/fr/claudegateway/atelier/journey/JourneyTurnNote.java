package fr.claudegateway.atelier.journey;

/**
 * <b>Le parcours joint au tour</b> (F-176) : l'agent lit à chaque message où en est le sujet.
 * Préfixé à la <b>consigne du tour</b>, jamais à la consigne système (le préfixe stable du cache,
 * F-134 / F-171) ; le message persisté reste la parole de l'utilisateur.
 *
 * <p>En mode Libre, rien — la consigne est inchangée à l'octet près (décision Q4) — sauf deux cas
 * prévus par la décision Q1 : le <b>premier message</b> d'un sujet (qualifier la demande) et une
 * <b>proposition</b> du mode guidé qui attend le choix de l'utilisateur.</p>
 */
public final class JourneyTurnNote {

    private static final String HEADER = "--- Parcours du sujet ---\n";
    private static final String FOOTER = "---\n\n";

    private JourneyTurnNote() {
    }

    /** Le bloc à préfixer, sans contexte de premier message. */
    public static String render(SubjectJourney journey) {
        return render(journey, false);
    }

    /**
     * Le bloc à préfixer, ou la chaîne vide.
     *
     * @param journey   le parcours du terminal (Libre si {@code null})
     * @param firstTurn vrai si c'est le premier message du sujet (aucun tour rejoué)
     */
    public static String render(SubjectJourney journey, boolean firstTurn) {
        if (journey != null && journey.isGuided() && journey.getPhase() != null) {
            return HEADER + "Mode GUIDÉ · phase : " + journey.getPhase().label() + ".\n" + FOOTER;
        }
        if (journey != null && journey.getGuidedProposedAt() != null) {
            return HEADER + "Mode LIBRE · ta proposition de passer en guidé attend le choix de "
                    + "l'utilisateur : n'en reparle pas, ne modifie rien de plus que ce qu'il demande.\n"
                    + FOOTER;
        }
        boolean declined = journey != null && journey.getGuidedDeclinedAt() != null;
        if (firstTurn && !declined) {
            return HEADER + "Mode LIBRE · premier message de ce sujet : qualifie la demande (question, "
                    + "petit geste ou chantier). Chantier → propose_guided_mode.\n" + FOOTER;
        }
        return "";
    }
}
