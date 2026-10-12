package fr.claudegateway.atelier.nextsteps;

import java.util.ArrayList;
import java.util.List;

import fr.claudegateway.atelier.journey.JourneyPhase;
import fr.claudegateway.atelier.journey.JourneyPlan;
import fr.claudegateway.atelier.journey.SubjectJourney;

/**
 * <b>Cap au service du chantier</b> (F-196 / SF-196-01, décisions PO 1, 2 et 5) : en chantier guidé
 * ouvert, chaque suite est rattachée par la gateway à une étape <b>du plan réel</b>, ou marquée
 * approfondissement. Le numéro proposé par le modèle n'est jamais cru tel quel : absent ou hors plan,
 * c'est un approfondissement.
 *
 * <p>Le chantier d'abord : les étapes passent en tête (ordre du modèle conservé), puis <b>un seul</b>
 * approfondissement, en dernier. Hors chantier ouvert (Libre, Clos, guidé sans plan) les suites
 * traversent sans rattachement — mode Libre inchangé.</p>
 *
 * <p>Fonctions pures, sans état ; jamais d'exception (un défaut de suggestion ne coûte pas le tour).</p>
 */
public final class ChantierAnchor {

    /** Titre d'étape rappelé sur l'étiquette, au plus. */
    public static final int MAX_STEP_TITLE_CHARS = 60;
    /** Approfondissements gardés au plus tant que le chantier est ouvert. */
    public static final int MAX_DEEPENINGS = 1;

    private ChantierAnchor() {
    }

    /**
     * L'avancement du chantier ouvert.
     *
     * @param number numéro du chantier (≥ 1)
     * @param done   étapes faites ou vérifiées
     * @param total  étapes du plan
     */
    public record Progress(int number, int done, int total) {
    }

    /**
     * Les suites rattachées, et l'avancement du chantier ({@code null} hors chantier ouvert).
     */
    public record Anchored(List<NextStep> steps, Progress chantier) {
    }

    /** Vrai si le chantier est ouvert : mode guidé, phase posée et non close. */
    static boolean isOpen(SubjectJourney journey) {
        return journey != null && journey.isGuided() && journey.getPhase() != null
                && journey.getPhase() != JourneyPhase.CLOS;
    }

    /** Rattache les suites au plan du chantier ouvert ; les laisse sans rattachement sinon. */
    public static Anchored apply(List<NextStep> steps, SubjectJourney journey) {
        List<NextStep> input = steps == null ? List.of() : steps;
        JourneyPlan plan = isOpen(journey) ? JourneyPlan.fromJson(journey.getPlanJson()) : JourneyPlan.EMPTY;
        if (plan.isEmpty()) {
            List<NextStep> plain = new ArrayList<>(input.size());
            for (NextStep step : input) {
                plain.add(step.anchored(null, null, null));
            }
            return new Anchored(List.copyOf(plain), null);
        }
        List<NextStep> chantier = new ArrayList<>();
        List<NextStep> deepenings = new ArrayList<>();
        for (NextStep step : input) {
            Integer n = step.proposedStep();
            if (n != null && n >= 1 && n <= plan.steps().size()) {
                String title = plan.steps().get(n - 1).title();
                chantier.add(step.anchored(NextStep.Scope.CHANTIER, n, shorten(title)));
            } else if (deepenings.size() < MAX_DEEPENINGS) {
                deepenings.add(step.anchored(NextStep.Scope.APPROFONDISSEMENT, null, null));
            }
        }
        List<NextStep> ordered = new ArrayList<>(chantier);
        ordered.addAll(deepenings);
        return new Anchored(List.copyOf(ordered), progress(journey, plan));
    }

    private static Progress progress(SubjectJourney journey, JourneyPlan plan) {
        int done = 0;
        for (JourneyPlan.Step s : plan.steps()) {
            if (s.status() == JourneyPlan.StepStatus.FAIT || s.status() == JourneyPlan.StepStatus.VERIFIE) {
                done++;
            }
        }
        return new Progress(Math.max(1, journey.getChantierNumber()), done, plan.steps().size());
    }

    private static String shorten(String title) {
        if (title == null) {
            return null;
        }
        String flat = title.replace('\n', ' ').strip();
        return flat.length() <= MAX_STEP_TITLE_CHARS ? flat
                : flat.substring(0, MAX_STEP_TITLE_CHARS - 1).strip() + "…";
    }
}
