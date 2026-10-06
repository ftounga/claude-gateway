package fr.claudegateway.atelier.journey;

/**
 * <b>Le parcours joint au tour</b> (F-176) : l'agent lit à chaque message où en est le sujet.
 * Préfixé à la <b>consigne du tour</b>, jamais à la consigne système (le préfixe stable du cache,
 * F-134 / F-171) ; le message persisté reste la parole de l'utilisateur.
 *
 * <p>En mode Libre, rien — la consigne est inchangée à l'octet près (décision Q4) — sauf deux cas
 * prévus par la décision Q1 : le <b>premier message</b> d'un sujet (qualifier la demande) et une
 * <b>proposition</b> du mode guidé qui attend le choix de l'utilisateur.</p>
 *
 * <p>En mode Guidé : la phase, et le plan (version, validé ou non, étapes) — borné.</p>
 */
public final class JourneyTurnNote {

    private static final String HEADER = "--- Parcours du sujet ---\n";
    private static final String FOOTER = "---\n\n";

    /** Bornes du plan rappelé : au-delà, le plan est compté, pas recopié. */
    static final int MAX_CHARS = 3_000;

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
            return HEADER + guided(journey) + FOOTER;
        }
        if (journey != null && journey.getGuidedProposedAt() != null) {
            return HEADER + "Mode LIBRE · ta proposition de passer en guidé attend le choix de "
                    + "l'utilisateur : n'en reparle pas, ne modifie rien de plus que ce qu'il demande.\n"
                    + FOOTER;
        }
        boolean declined = journey != null && journey.getGuidedDeclinedAt() != null;
        if (journey != null && !declined && journey.getPhase() == JourneyPhase.CLOS && journey.getChantierNumber() > 0) {
            // SF-176-11 : le chantier précédent est clos ; un chantier distinct en ouvre un nouveau.
            return HEADER + "Mode LIBRE · chantier " + journey.getChantierNumber() + " clos. Si la demande "
                    + "ouvre un chantier distinct, propose_guided_mode (un nouveau chantier, plan neuf).\n"
                    + FOOTER;
        }
        if (firstTurn && !declined) {
            return HEADER + "Mode LIBRE · premier message de ce sujet : qualifie la demande (question, "
                    + "petit geste ou chantier). Chantier → propose_guided_mode.\n" + FOOTER;
        }
        return "";
    }

    private static String guided(SubjectJourney journey) {
        StringBuilder note = new StringBuilder();
        note.append("Mode GUIDÉ · phase : ").append(journey.getPhase().label()).append(".\n");
        if (journey.getDiagnosis() != null) {
            String diagnosis = journey.getDiagnosis();
            if (diagnosis.length() > 500) {
                diagnosis = diagnosis.substring(0, 500) + "…";
            }
            note.append("Diagnostic (confiance ").append(journey.getDiagnosisConfidence()).append(") : ")
                    .append(diagnosis.replace('\n', ' ')).append('\n');
            if (journey.getPhase() == JourneyPhase.INVESTIGATION && journey.getDiagnosisProposedAt() != null) {
                note.append("Ton diagnostic attend la confirmation de l'utilisateur (« Prêt à planifier »).\n");
            }
        }
        if (journey.getCloseProposedAt() != null && journey.getPhase() != JourneyPhase.CLOS) {
            note.append("Toutes les étapes sont vérifiées : la clôture attend la confirmation de l'utilisateur.\n");
        }
        JourneyPlan plan = JourneyPlan.fromJson(journey.getPlanJson());
        if (plan.isEmpty()) {
            if (journey.getPhase() == JourneyPhase.INVESTIGATION) {
                note.append("Aucun plan : quand le diagnostic est sûr, pose-le avec submit_diagnosis.\n");
            } else if (journey.getPhase() == JourneyPhase.PLAN) {
                note.append("Aucun plan : pose-le avec set_subject_plan.\n");
            }
            return note.toString();
        }
        Integer validated = journey.getValidatedVersion();
        boolean current = validated != null && validated == journey.getPlanVersion();
        note.append("Plan v").append(journey.getPlanVersion());
        if (current) {
            note.append(" — VALIDÉ par l'utilisateur.\n");
        } else if (validated != null) {
            note.append(" — AMENDEMENT EN ATTENTE DE VALIDATION (v").append(validated)
                    .append(" était validé) : ne modifie rien avant sa validation.\n");
        } else {
            note.append(" — EN ATTENTE DE VALIDATION : ne modifie rien avant sa validation.\n");
        }
        int index = 0;
        for (JourneyPlan.Step step : plan.steps()) {
            index++;
            StringBuilder line = new StringBuilder();
            line.append(index).append(". [").append(statusLabel(step.status())).append("] ")
                    .append(step.title()).append(" · ").append(step.risk().label());
            if (step.waitsOn() != null) {
                line.append(" · attend : ").append(step.waitsOn());
            }
            line.append('\n');
            if (note.length() + line.length() > MAX_CHARS) {
                note.append("… et ").append(plan.steps().size() - index + 1).append(" étape(s) de plus.\n");
                break;
            }
            note.append(line);
        }
        return note.toString();
    }

    private static String statusLabel(JourneyPlan.StepStatus status) {
        return switch (status) {
            case A_FAIRE -> "à faire";
            case FAIT -> "fait";
            case VERIFIE -> "vérifié";
            case ECHEC -> "échec";
        };
    }
}
