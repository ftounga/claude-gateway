package fr.claudegateway.atelier.journey;

/**
 * <b>La porte du parcours guidé</b> (F-176 / SF-176-04, décisions Q2 et Q3) — tenue par le harnais,
 * comme la porte de fin de tour : une consigne se fait ignorer, une porte non.
 *
 * <p>En mode <b>Guidé</b> : la lecture et les notes du sujet sont toujours libres ; <b>toute autre
 * modification</b> (édition en branche comprise, et a fortiori l'externe ou l'irréversible) n'est
 * permise qu'en phase <b>Exécution</b>, sur la version <b>validée</b> du plan. Un amendement non
 * revalidé ferme la porte jusqu'au clic de l'utilisateur.</p>
 *
 * <p>En mode <b>Libre</b> : la porte n'existe pas (Q4) — les garde-fous existants seuls s'appliquent.</p>
 */
public final class JourneyGate {

    private JourneyGate() {
    }

    /**
     * Le motif du refus rendu au modèle, ou {@code null} si l'appel passe.
     *
     * @param journey le parcours du terminal ({@code null} = Libre)
     * @param risk    la classe de l'appel ({@code null} = non soumis à la porte)
     */
    public static String refusal(SubjectJourney journey, JourneyPlan.Risk risk) {
        if (journey == null || !journey.isGuided() || journey.getPhase() == null || risk == null
                || risk == JourneyPlan.Risk.LECTURE || risk == JourneyPlan.Risk.NOTES) {
            return null;
        }
        Integer validated = journey.getValidatedVersion();
        boolean planCurrent = validated != null && validated == journey.getPlanVersion();
        if (journey.getPhase() == JourneyPhase.EXECUTION && planCurrent) {
            return null;
        }
        String head = "PORTE DU PARCOURS GUIDÉ — cette action (" + risk.label() + ") n'a pas été exécutée. ";
        return head + switch (journey.getPhase()) {
            case INVESTIGATION -> "Phase Investigation : la lecture et les notes du sujet (STATE.md, "
                    + "PLAN-ACTION.md) sont libres, mais rien ne se modifie avant un plan validé. Poursuis "
                    + "l'investigation, puis pose ton diagnostic et ton plan.";
            case PLAN -> (validated != null
                    ? "L'amendement du plan (v" + journey.getPlanVersion() + ") attend la validation de "
                            + "l'utilisateur."
                    : "Le plan (v" + journey.getPlanVersion() + ") attend la validation de l'utilisateur.")
                    + " Attends son clic : n'essaie pas d'autre chemin pour modifier, et dis-lui que le plan "
                    + "attend sa validation.";
            case EXECUTION -> "Aucun plan validé ne couvre cette modification : amende le plan avec "
                    + "set_subject_plan, il repassera par la validation de l'utilisateur.";
            case VERIFICATION -> "Phase Vérification : on vérifie, on ne modifie plus. Si une correction "
                    + "est nécessaire, amende le plan (set_subject_plan) ou reviens en investigation.";
            case CLOS -> "Le sujet est clos : rien ne se modifie plus. S'il faut reprendre, l'utilisateur "
                    + "rouvre le sujet en mode guidé.";
        };
    }
}
