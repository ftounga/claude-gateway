package fr.claudegateway.atelier.autonomy;

import java.util.ArrayList;
import java.util.List;

import fr.claudegateway.atelier.journey.JourneyPlan;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyChantier;

/**
 * <b>Un chantier ouvert, vu par la course autonome</b> (F-219 / SF-219-02) : son plan, sa clôture proposée,
 * et une mesure d'<b>avancée</b> — de quoi juger, après chaque tour, si la course continue.
 *
 * @param open          le chantier est toujours ouvert
 * @param title         son titre
 * @param plan          son plan courant
 * @param closeProposed la clôture est proposée (toutes les étapes vérifiées)
 */
public record ChantierSnapshot(boolean open, String title, JourneyPlan plan, boolean closeProposed) {

    /** Un chantier qui n'est plus ouvert. */
    static final ChantierSnapshot GONE = new ChantierSnapshot(false, null, JourneyPlan.EMPTY, false);

    /** Le chantier {@code number} du parcours (focus ou autre chantier ouvert), ou {@link #GONE}. */
    public static ChantierSnapshot of(SubjectJourney journey, int number) {
        if (journey == null || journey.getCreatedAt() == null) {
            return GONE;
        }
        if (journey.hasOpenFocus() && Math.max(1, journey.getChantierNumber()) == number) {
            return new ChantierSnapshot(true, journey.getChantierTitle(), JourneyPlan.fromJson(journey.getPlanJson()),
                    journey.getCloseProposedAt() != null);
        }
        for (SubjectJourneyChantier row : journey.getOtherOpenChantiers()) {
            if (row.getNumber() == number) {
                return new ChantierSnapshot(true, row.getTitle(), JourneyPlan.fromJson(row.getPlanJson()),
                        row.getCloseProposedAt() != null);
            }
        }
        return GONE;
    }

    /** Étapes faites ou vérifiées. */
    public int done() {
        return (int) plan.steps().stream().filter(JourneyPlan.Step::isDone).count();
    }

    /** Étapes du plan. */
    public int total() {
        return plan.steps().size();
    }

    /** Toutes les étapes vérifiées (et au moins une). */
    public boolean allVerified() {
        return !plan.isEmpty()
                && plan.steps().stream().allMatch(s -> s.status() == JourneyPlan.StepStatus.VERIFIE);
    }

    /** Les étapes qui restent (non vérifiées), rang à partir de 1. */
    public List<Integer> remaining() {
        List<Integer> ranks = new ArrayList<>();
        for (int i = 0; i < plan.steps().size(); i++) {
            if (plan.steps().get(i).status() != JourneyPlan.StepStatus.VERIFIE) {
                ranks.add(i + 1);
            }
        }
        return List.copyOf(ranks);
    }

    /**
     * L'avancée du plan : une étape faite vaut 2, vérifiée 3, chaque sous-étape cochée 1. Un tour qui ne la
     * fait pas monter n'a rien fait avancer.
     */
    public int progress() {
        int score = 0;
        for (JourneyPlan.Step step : plan.steps()) {
            score += switch (step.status()) {
                case FAIT -> 2;
                case VERIFIE -> 3;
                default -> 0;
            };
            score += step.subStepsDone();
        }
        return score;
    }
}
