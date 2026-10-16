package fr.claudegateway.atelier.nextsteps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import fr.claudegateway.atelier.journey.JourneyPhase;
import fr.claudegateway.atelier.journey.JourneyPlan;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyChantier;

/**
 * <b>Cap au service du chantier</b> (F-196 / SF-196-01, décisions PO 1, 2 et 5) : en chantier guidé
 * ouvert, chaque suite est rattachée par la gateway à une étape <b>du plan réel</b>, ou marquée
 * approfondissement. Le numéro proposé par le modèle n'est jamais cru tel quel : absent ou hors plan,
 * c'est un approfondissement.
 *
 * <p>F-206 / SF-206-03 (décision PO) : un sujet porte plusieurs chantiers ouverts. Une suite qui relève
 * d'un <b>autre</b> chantier ouvert ({@code chantier} proposé) est rattachée à ce chantier, validée contre
 * <b>son</b> plan — « Hors plan » est réservé à ce qui ne relève d'aucun chantier ouvert.</p>
 *
 * <p>Le chantier d'abord : les suites du focus passent en tête, puis celles des autres chantiers (ordre
 * du modèle conservé), puis <b>un seul</b> approfondissement, en dernier. Hors chantier ouvert (Libre,
 * Clos, guidé sans aucun plan) les suites traversent sans rattachement — mode Libre inchangé.</p>
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
     * L'avancement du chantier au focus.
     *
     * @param number numéro du chantier (≥ 1)
     * @param done   étapes faites ou vérifiées
     * @param total  étapes du plan
     * @param others l'avancement des autres chantiers ouverts portant un plan (F-206), ou {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Progress(int number, int done, int total, List<Progress> others) {

        /** L'avancement d'un chantier seul (F-196). */
        public Progress(int number, int done, int total) {
            this(number, done, total, null);
        }
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

    /** Rattache les suites aux plans des chantiers ouverts ; les laisse sans rattachement sinon. */
    public static Anchored apply(List<NextStep> steps, SubjectJourney journey) {
        List<NextStep> input = steps == null ? List.of() : steps;
        Map<Integer, JourneyPlan> plans = plans(journey);
        if (plans.values().stream().allMatch(JourneyPlan::isEmpty)) {
            List<NextStep> plain = new ArrayList<>(input.size());
            for (NextStep step : input) {
                plain.add(step.anchored(null, null, null));
            }
            return new Anchored(List.copyOf(plain), null);
        }
        int focus = Math.max(1, journey.getChantierNumber());
        List<NextStep> focusSteps = new ArrayList<>();
        List<NextStep> otherSteps = new ArrayList<>();
        List<NextStep> deepenings = new ArrayList<>();
        for (NextStep step : input) {
            Integer proposed = step.proposedChantier();
            int target = proposed == null ? focus : proposed;
            JourneyPlan plan = plans.get(target);
            Integer n = step.proposedStep();
            List<NextStep> bucket = target == focus ? focusSteps : otherSteps;
            if (plan != null && n != null && n >= 1 && n <= plan.steps().size()) {
                bucket.add(step.anchored(NextStep.Scope.CHANTIER, n, shorten(plan.steps().get(n - 1).title()),
                        target));
            } else if (plan != null && target != focus) {
                // F-206 : la suite relève d'un autre chantier ouvert, sans étape précise.
                bucket.add(step.anchored(NextStep.Scope.CHANTIER, null, null, target));
            } else if (deepenings.size() < MAX_DEEPENINGS) {
                deepenings.add(step.anchored(NextStep.Scope.APPROFONDISSEMENT, null, null));
            }
        }
        List<NextStep> ordered = new ArrayList<>(focusSteps);
        ordered.addAll(otherSteps);
        ordered.addAll(deepenings);
        return new Anchored(List.copyOf(ordered), progress(focus, plans));
    }

    /** Les plans des chantiers ouverts, focus d'abord (vide hors chantier guidé ouvert). */
    private static Map<Integer, JourneyPlan> plans(SubjectJourney journey) {
        Map<Integer, JourneyPlan> plans = new LinkedHashMap<>();
        if (!isOpen(journey)) {
            return plans;
        }
        plans.put(Math.max(1, journey.getChantierNumber()), JourneyPlan.fromJson(journey.getPlanJson()));
        for (SubjectJourneyChantier other : journey.getOtherOpenChantiers()) {
            plans.putIfAbsent(other.getNumber(), JourneyPlan.fromJson(other.getPlanJson()));
        }
        return plans;
    }

    private static Progress progress(int focus, Map<Integer, JourneyPlan> plans) {
        List<Progress> others = new ArrayList<>();
        for (Map.Entry<Integer, JourneyPlan> e : plans.entrySet()) {
            if (e.getKey() != focus && !e.getValue().isEmpty()) {
                others.add(single(e.getKey(), e.getValue()));
            }
        }
        Progress mine = single(focus, plans.get(focus));
        return others.isEmpty() ? mine : new Progress(mine.number(), mine.done(), mine.total(), List.copyOf(others));
    }

    private static Progress single(int number, JourneyPlan plan) {
        int done = 0;
        for (JourneyPlan.Step s : plan.steps()) {
            if (s.status() == JourneyPlan.StepStatus.FAIT || s.status() == JourneyPlan.StepStatus.VERIFIE) {
                done++;
            }
        }
        return new Progress(number, done, plan.steps().size());
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
