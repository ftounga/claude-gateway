package fr.claudegateway.atelier.journey;

/**
 * <b>Le parcours joint au tour</b> (F-176) : en mode Guidé, l'agent lit à chaque message où en est le
 * sujet. Préfixé à la <b>consigne du tour</b>, jamais à la consigne système (le préfixe stable du
 * cache, F-134 / F-171) ; le message persisté reste la parole de l'utilisateur.
 *
 * <p>En mode Libre : <b>rien</b> — la consigne est inchangée à l'octet près (décision Q4).</p>
 */
public final class JourneyTurnNote {

    private JourneyTurnNote() {
    }

    /** Le bloc à préfixer, ou la chaîne vide (Libre, ou rien à dire). */
    public static String render(SubjectJourney journey) {
        if (journey == null || !journey.isGuided() || journey.getPhase() == null) {
            return "";
        }
        StringBuilder note = new StringBuilder();
        note.append("--- Parcours du sujet ---\n");
        note.append("Mode GUIDÉ · phase : ").append(journey.getPhase().label()).append(".\n");
        note.append("---\n\n");
        return note.toString();
    }
}
