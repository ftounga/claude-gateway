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
            if (journey.getOtherOpenChantiers().isEmpty()) {
                return HEADER + guided(journey) + proposalPending(journey) + FOOTER;
            }
            return HEADER + several(journey) + FOOTER;
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

    /** Résumés d'autres chantiers rappelés au plus ; au-delà, comptés. */
    static final int MAX_OTHERS = 6;

    /** Titre d'un chantier résumé, au plus. */
    static final int MAX_TITLE = 80;

    /** F-206 : une proposition de chantier de plus attend le choix de l'utilisateur. */
    private static String proposalPending(SubjectJourney journey) {
        return journey.getGuidedProposedAt() == null ? ""
                : "Ta proposition d'ouvrir un chantier de plus attend le choix de l'utilisateur : n'en reparle pas.\n";
    }

    /**
     * F-206 : plusieurs chantiers ouverts — le focus détaillé d'abord, les autres résumés d'une ligne.
     */
    private static String several(SubjectJourney journey) {
        StringBuilder note = new StringBuilder();
        java.util.List<SubjectJourneyChantier> others = journey.getOtherOpenChantiers();
        note.append("Chantiers ouverts : ").append(others.size() + 1).append(". Focus = Chantier ")
                .append(journey.getChantierNumber()).append(" : les outils du parcours le visent par défaut ; ")
                .append("chantier: N pour en viser un autre.\n");
        note.append("== Chantier ").append(journey.getChantierNumber()).append(" (focus) — ")
                .append(title(journey.getChantierTitle(), journey.getChantierNumber())).append(" ==\n");
        note.append(guided(journey));
        int shown = 0;
        for (SubjectJourneyChantier other : others) {
            if (shown == MAX_OTHERS) {
                note.append("… et ").append(others.size() - shown).append(" autre(s) chantier(s) ouvert(s).\n");
                break;
            }
            note.append(summary(other));
            shown++;
        }
        note.append(proposalPending(journey));
        return note.toString();
    }

    private static String summary(SubjectJourneyChantier c) {
        StringBuilder line = new StringBuilder("== Chantier ").append(c.getNumber()).append(" — ")
                .append(title(c.getTitle(), c.getNumber())).append(" · ")
                .append(c.getPhase() == null ? "?" : c.getPhase().label());
        JourneyPlan plan = JourneyPlan.fromJson(c.getPlanJson());
        int version = c.getPlanVersion() == null ? 0 : c.getPlanVersion();
        boolean current = c.getValidatedVersion() != null && c.getValidatedVersion() == version;
        if (!plan.isEmpty()) {
            int done = 0;
            for (JourneyPlan.Step s : plan.steps()) {
                if (s.status() == JourneyPlan.StepStatus.FAIT || s.status() == JourneyPlan.StepStatus.VERIFIE) {
                    done++;
                }
            }
            line.append(" · plan v").append(version).append(current ? " validé" : " en attente de validation")
                    .append(" · ").append(done).append('/').append(plan.steps().size()).append(" étapes");
        }
        if (c.getPhase() == JourneyPhase.INVESTIGATION && c.getDiagnosisProposedAt() != null) {
            line.append(" · diagnostic en attente de confirmation");
        }
        if (c.getCloseProposedAt() != null) {
            line.append(" · clôture proposée");
        }
        return line.append(" ==\n").toString();
    }

    private static String title(String title, int number) {
        if (title == null || title.isBlank()) {
            return "Chantier " + number;
        }
        String flat = title.replace('\n', ' ').strip();
        return flat.length() <= MAX_TITLE ? flat : flat.substring(0, MAX_TITLE - 1).strip() + "…";
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
