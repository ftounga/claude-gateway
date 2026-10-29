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
 * <p>F-209 / SF-209-01 : <b>deux colonnes</b> quand un chantier est ouvert, plafonds séparés — colonne
 * {@code CHANTIER} (au plus {@link #MAX_CHANTIER_COLUMN}, suites rattachées à une étape du focus, dans un
 * ordre imposé : prochaine étape débloquée, ce qui débloque une étape en attente, vérification de ce qui
 * vient d'être fait) et colonne {@code HORS} (au plus {@link #MAX_HORS_COLUMN} : approfondissements,
 * messages hors plan, au plus {@link #MAX_OTHER_CHANTIER} suite d'un autre chantier ouvert). Une étape
 * bloquée porte ce qui la bloque ; les prérequis d'une étape sont calculés depuis le plan, jamais
 * devinés. Tant qu'une étape reste à faire en Exécution, la colonne Chantier n'est jamais vide (repli
 * « Continuer : étape M »). Sans chantier ouvert : une seule colonne, {@link #MAX_SINGLE_COLUMN} au plus.</p>
 *
 * <p>Fonctions pures, sans état ; jamais d'exception (un défaut de suggestion ne coûte pas le tour).</p>
 */
public final class ChantierAnchor {

    /** Titre d'étape rappelé sur l'étiquette, au plus. */
    public static final int MAX_STEP_TITLE_CHARS = 60;
    /** Sans chantier ouvert : une seule colonne, 4 options au plus (F-192, inchangé). */
    public static final int MAX_SINGLE_COLUMN = 4;
    /** Colonne « Chantier » : au plus 4 options (F-209). */
    public static final int MAX_CHANTIER_COLUMN = 4;
    /** Colonne « Hors chantier » : au plus 3 options (F-209). */
    public static final int MAX_HORS_COLUMN = 3;
    /** Suites d'un AUTRE chantier ouvert, au plus, dans la colonne « Hors chantier » (F-209). */
    public static final int MAX_OTHER_CHANTIER = 1;
    /** Prérequis rappelés au plus pour une option (F-209). */
    public static final int MAX_PREREQUISITES = 8;

    private ChantierAnchor() {
    }

    /**
     * L'avancement du chantier au focus.
     *
     * @param number numéro du chantier (≥ 1)
     * @param done   étapes faites ou vérifiées
     * @param total  étapes du plan
     * @param others l'avancement des autres chantiers ouverts portant un plan (F-206), ou {@code null}
     * @param title  le titre du chantier (F-209 : en-tête de la colonne « Chantier N — titre »), ou {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Progress(int number, int done, int total, List<Progress> others, String title) {

        /** L'avancement d'un chantier seul (F-196). */
        public Progress(int number, int done, int total) {
            this(number, done, total, null, null);
        }

        /** L'avancement du focus et des autres chantiers ouverts (F-206). */
        public Progress(int number, int done, int total, List<Progress> others) {
            this(number, done, total, others, null);
        }
    }

    /**
     * Les suites rattachées, et l'avancement du chantier ({@code null} hors chantier ouvert).
     */
    public record Anchored(List<NextStep> steps, Progress chantier) {
    }

    /**
     * <b>Les suites d'un volet du split</b> (F-221 / SF-221-05) : seules celles du chantier du volet restent —
     * la colonne « Hors chantier » est retirée. Une suite sans colonne (aucun chantier ouvert) reste.
     */
    public static Anchored chantierOnly(Anchored anchored) {
        if (anchored == null) {
            return null;
        }
        List<NextStep> kept = anchored.steps().stream()
                .filter(step -> step.column() != NextStep.Column.HORS)
                .toList();
        return new Anchored(kept, anchored.chantier());
    }

    /** Vrai si le chantier est ouvert : mode guidé, phase posée et non close. */
    static boolean isOpen(SubjectJourney journey) {
        return journey != null && journey.isGuided() && journey.getPhase() != null
                && journey.getPhase() != JourneyPhase.CLOS;
    }

    /** Rattache les suites aux plans des chantiers ouverts ; les laisse sans rattachement sinon. */
    public static Anchored apply(List<NextStep> steps, SubjectJourney journey) {
        return apply(steps, journey, Map.of());
    }

    /**
     * Même chose, avec l'état des attentes dont dépendent les étapes (clé → {@code A_FAIRE}, {@code DEMANDE},
     * {@code FAIT}, {@code ANNULE}) : une étape dont l'attente est ouverte est bloquée (F-209).
     */
    public static Anchored apply(List<NextStep> steps, SubjectJourney journey, Map<String, String> waits) {
        List<NextStep> input = steps == null ? List.of() : steps;
        Map<String, String> waitStates = waits == null ? Map.of() : waits;
        Map<Integer, JourneyPlan> plans = plans(journey);
        if (plans.values().stream().allMatch(JourneyPlan::isEmpty)) {
            List<NextStep> plain = new ArrayList<>(input.size());
            for (NextStep step : input) {
                if (plain.size() == MAX_SINGLE_COLUMN) {
                    break;
                }
                plain.add(step.anchored(null, null, null));
            }
            return new Anchored(List.copyOf(plain), null);
        }
        int focus = Math.max(1, journey.getChantierNumber());
        JourneyPlan focusPlan = plans.get(focus);
        List<NextStep> focusSteps = new ArrayList<>();
        List<NextStep> hors = new ArrayList<>();
        int others = 0;
        for (NextStep step : input) {
            Integer proposed = step.proposedChantier();
            int target = proposed == null ? focus : proposed;
            JourneyPlan plan = plans.get(target);
            Integer n = step.proposedStep();
            boolean inPlan = plan != null && n != null && n >= 1 && n <= plan.steps().size();
            if (target == focus && inPlan) {
                NextStep anchored = step.anchored(NextStep.Scope.CHANTIER, n, shorten(plan.steps().get(n - 1).title()),
                        target);
                focusSteps.add(anchored.placed(NextStep.Column.CHANTIER, blockedBy(plan, n - 1, waitStates),
                        prerequisites(plan, n - 1)));
            } else if (plan != null && target != focus) {
                // F-206 : la suite relève d'un autre chantier ouvert — F-209 : à droite, une seule.
                if (others < MAX_OTHER_CHANTIER && hors.size() < MAX_HORS_COLUMN) {
                    NextStep anchored = inPlan
                            ? step.anchored(NextStep.Scope.CHANTIER, n, shorten(plan.steps().get(n - 1).title()), target)
                            : step.anchored(NextStep.Scope.CHANTIER, null, null, target);
                    hors.add(anchored.placed(NextStep.Column.HORS,
                            inPlan ? blockedBy(plan, n - 1, waitStates) : null, null));
                    others++;
                }
            } else if (hors.size() < MAX_HORS_COLUMN) {
                hors.add(step.anchored(NextStep.Scope.APPROFONDISSEMENT, null, null)
                        .placed(NextStep.Column.HORS, null, null));
            }
        }
        List<NextStep> chantierColumn = order(focusSteps, focusPlan);
        if (chantierColumn.isEmpty()) {
            NextStep fallback = fallback(journey, focus, focusPlan, waitStates);
            if (fallback != null) {
                chantierColumn = List.of(fallback);
            }
        }
        List<NextStep> ordered = new ArrayList<>(chantierColumn.subList(0, Math.min(MAX_CHANTIER_COLUMN,
                chantierColumn.size())));
        ordered.addAll(hors);
        return new Anchored(List.copyOf(ordered), progress(focus, plans, journey));
    }

    /**
     * L'ordre imposé de la colonne « Chantier » (F-209, stable) : la prochaine étape débloquée d'abord (par
     * numéro), puis ce qui débloque une étape en attente, puis la vérification de ce qui vient d'être fait,
     * puis le reste (ordre du modèle).
     */
    private static List<NextStep> order(List<NextStep> steps, JourneyPlan plan) {
        List<NextStep> sorted = new ArrayList<>(steps);
        sorted.sort(java.util.Comparator.comparingInt((NextStep s) -> rank(s, plan))
                .thenComparingInt(s -> rank(s, plan) == 0 ? s.stepIndex() : 0));
        return sorted;
    }

    private static int rank(NextStep step, JourneyPlan plan) {
        JourneyPlan.Step target = plan.steps().get(step.stepIndex() - 1);
        boolean todo = target.status() == JourneyPlan.StepStatus.A_FAIRE
                || target.status() == JourneyPlan.StepStatus.ECHEC
                || target.status() == JourneyPlan.StepStatus.BLOQUE; // F-219 : à faire, mais bloquée
        if (todo) {
            return step.blockedBy() == null && target.status() != JourneyPlan.StepStatus.BLOQUE ? 0 : 1;
        }
        return target.status() == JourneyPlan.StepStatus.FAIT ? 2 : 3;
    }

    /** Ce qui bloque l'étape {@code index} : dépendances non faites, attente ouverte (F-209). */
    static List<String> blockedBy(JourneyPlan plan, int index, Map<String, String> waits) {
        JourneyPlan.Step step = plan.steps().get(index);
        if (step.isDone()) {
            return List.of();
        }
        List<String> blocked = new ArrayList<>();
        for (int rank : plan.blockingSteps(index)) {
            blocked.add("étape " + rank);
        }
        String wait = step.waitsOn() == null ? null : waits.get(step.waitsOn());
        if ("A_FAIRE".equals(wait) || "DEMANDE".equals(wait)) {
            blocked.add("attente " + step.waitsOn());
        }
        return blocked;
    }

    /**
     * Les prérequis de l'étape {@code index} (F-209, décision 4) : ses dépendances DÉCLARÉES non faites, et
     * les leurs, transitivement — jamais devinées. Niveau = 1 + le plus haut niveau de ses propres
     * prérequis ; deux étapes EXTERNE ne partagent jamais un niveau (la seconde passe au niveau suivant).
     */
    static List<NextStep.Prerequisite> prerequisites(JourneyPlan plan, int index) {
        if (plan.steps().get(index).isDone()) {
            return List.of();
        }
        java.util.TreeSet<Integer> closure = new java.util.TreeSet<>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>(plan.pendingDeclared(index));
        while (!queue.isEmpty() && closure.size() < MAX_PREREQUISITES) {
            int rank = queue.poll();
            if (closure.add(rank)) {
                queue.addAll(plan.pendingDeclared(rank - 1));
            }
        }
        if (closure.isEmpty()) {
            return List.of();
        }
        Map<Integer, Integer> levels = new LinkedHashMap<>();
        for (int rank : closure) { // croissant : les dépendances (antérieures) ont déjà leur niveau
            int level = 1;
            for (int dep : plan.steps().get(rank - 1).dependsOn()) {
                if (levels.containsKey(dep)) {
                    level = Math.max(level, levels.get(dep) + 1);
                }
            }
            levels.put(rank, level);
        }
        // Regroupe par niveau, puis rétrograde toute EXTERNE en trop dans un groupe à elle.
        java.util.TreeMap<Integer, List<Integer>> byLevel = new java.util.TreeMap<>();
        levels.forEach((rank, level) -> byLevel.computeIfAbsent(level, l -> new ArrayList<>()).add(rank));
        List<NextStep.Prerequisite> result = new ArrayList<>();
        int level = 0;
        for (List<Integer> group : byLevel.values()) {
            level++;
            boolean external = false;
            List<Integer> demoted = new ArrayList<>();
            for (int rank : group) {
                JourneyPlan.Step s = plan.steps().get(rank - 1);
                if (s.risk() == JourneyPlan.Risk.EXTERNE) {
                    if (external) {
                        demoted.add(rank);
                        continue;
                    }
                    external = true;
                }
                result.add(prerequisite(plan, rank, level));
            }
            for (int rank : demoted) {
                level++;
                result.add(prerequisite(plan, rank, level));
            }
        }
        return List.copyOf(result);
    }

    private static NextStep.Prerequisite prerequisite(JourneyPlan plan, int rank, int level) {
        JourneyPlan.Step s = plan.steps().get(rank - 1);
        return new NextStep.Prerequisite(rank, shorten(s.title()), s.risk().name(), level);
    }

    /**
     * Le repli « jamais vide » (F-209, décision 7) : en Exécution sur le plan validé, s'il reste une étape à
     * faire et que le modèle n'a rien rattaché au chantier, une option dérivée du plan — la première étape à
     * faire débloquée, sinon la première à faire. {@code null} sinon.
     */
    private static NextStep fallback(SubjectJourney journey, int focus, JourneyPlan plan, Map<String, String> waits) {
        boolean executing = journey.getPhase() == JourneyPhase.EXECUTION && journey.getValidatedVersion() != null
                && journey.getValidatedVersion() == journey.getPlanVersion();
        if (!executing || plan == null || plan.isEmpty()) {
            return null;
        }
        int chosen = -1;
        for (int i = 0; i < plan.steps().size(); i++) {
            JourneyPlan.StepStatus status = plan.steps().get(i).status();
            if (status != JourneyPlan.StepStatus.A_FAIRE && status != JourneyPlan.StepStatus.ECHEC) {
                continue;
            }
            if (chosen < 0) {
                chosen = i;
            }
            if (blockedBy(plan, i, waits).isEmpty()) {
                chosen = i;
                break;
            }
        }
        if (chosen < 0) {
            return null;
        }
        JourneyPlan.Step step = plan.steps().get(chosen);
        int m = chosen + 1;
        String title = "Continuer : étape " + m;
        NextStep derived = new NextStep(title, "L’étape " + m + " du plan reste à faire.", NextStep.Label.CONTINUER,
                "Chantier " + focus + " — fais l’étape " + m + " : " + step.title());
        return derived.anchored(NextStep.Scope.CHANTIER, m, shorten(step.title()), focus)
                .placed(NextStep.Column.CHANTIER, blockedBy(plan, chosen, waits), prerequisites(plan, chosen));
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

    private static Progress progress(int focus, Map<Integer, JourneyPlan> plans, SubjectJourney journey) {
        Map<Integer, String> titles = new LinkedHashMap<>();
        titles.put(focus, journey.getChantierTitle());
        for (SubjectJourneyChantier other : journey.getOtherOpenChantiers()) {
            titles.putIfAbsent(other.getNumber(), other.getTitle());
        }
        List<Progress> others = new ArrayList<>();
        for (Map.Entry<Integer, JourneyPlan> e : plans.entrySet()) {
            if (e.getKey() != focus && !e.getValue().isEmpty()) {
                others.add(single(e.getKey(), e.getValue(), titles.get(e.getKey())));
            }
        }
        Progress mine = single(focus, plans.get(focus), titles.get(focus));
        return others.isEmpty() ? mine
                : new Progress(mine.number(), mine.done(), mine.total(), List.copyOf(others), mine.title());
    }

    private static Progress single(int number, JourneyPlan plan, String title) {
        int done = 0;
        for (JourneyPlan.Step s : plan.steps()) {
            if (s.status() == JourneyPlan.StepStatus.FAIT || s.status() == JourneyPlan.StepStatus.VERIFIE) {
                done++;
            }
        }
        return new Progress(number, done, plan.steps().size(), null, title);
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
