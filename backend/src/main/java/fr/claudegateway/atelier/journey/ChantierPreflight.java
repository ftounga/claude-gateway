package fr.claudegateway.atelier.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>Le contrôle avant départ d'un chantier</b> (F-219 / SF-219-01) : « Prêt à partir » en autonome, ou la
 * liste précise de ce qui l'empêche. <b>Calculé, jamais deviné</b> : tout se lit dans le plan persisté, l'état
 * des attentes F-175 et l'état du tour vivant.
 *
 * <p>Calcul pur ({@link #evaluate}) : aucune dépendance, aucun accès aux données — l'appelant a déjà vérifié
 * la propriété du terminal et lu ce qu'il fallait.</p>
 *
 * @param number  le numéro du chantier
 * @param ready   vrai si rien n'empêche le départ
 * @param reasons ce qui l'empêche, dans l'ordre de lecture (vide si prêt)
 * @param advance s'il peut « avancer jusqu'au blocage », et ce qui restera (SF-219-05)
 */
public record ChantierPreflight(int number, boolean ready, List<Reason> reasons, Advance advance) {

    /** Préflight strict seul (le calcul d'« avancer » n'est pas demandé). */
    public ChantierPreflight(int number, boolean ready, List<Reason> reasons) {
        this(number, ready, reasons, Advance.NONE);
    }

    /**
     * <b>Avancer jusqu'au blocage</b> (F-219 / SF-219-05) : la condition de départ du mode semi-autonome.
     *
     * @param possible  plan validé à sa version courante, Exécution ou Vérification, et au moins une étape à la
     *                  portée de l'outil
     * @param toolSteps les étapes à la portée de l'outil (rang à partir de 1) : à faire avec la marque
     *                  « qui débloque ? » {@code OUTIL}, ou faites (l'outil les vérifie)
     * @param remaining ce qui restera : les causes premières {@code MOI} (« Ça attend votre décision ») et
     *                  {@code AUTRUI} (« On attend quelqu'un »), dans l'ordre du plan
     * @param label     la phrase lisible telle quelle (pourquoi c'est possible, ou non)
     */
    public record Advance(boolean possible, List<Integer> toolSteps, List<ChantierUnblocker.Mark> remaining,
                          String label) {

        /** Rien n'est calculé. */
        public static final Advance NONE = new Advance(false, List.of(), List.of(), null);
    }

    /** Les codes de raison — stables, lus par l'écran et par F-220. */
    public enum Code {
        /** Le sujet n'est pas en mode guidé, ou le chantier n'a pas de plan. */
        NOT_GUIDED,
        /** Pas de plan validé à sa version courante (jamais validé, ou amendement en attente). */
        PLAN_NOT_VALIDATED,
        /** Le chantier n'est ni en Exécution ni en Vérification. */
        NOT_EXECUTING,
        /** Toutes les étapes sont vérifiées : rien à exécuter. */
        NOTHING_LEFT,
        /** Une étape est marquée BLOQUÉE. */
        STEP_BLOCKED,
        /** Une étape est en échec. */
        STEP_FAILED,
        /** Une étape restante attend une attente F-175 encore ouverte. */
        WAIT_OPEN,
        /** Une étape restante attend un input que personne ne suit (aucune attente à sa clé). */
        MISSING_INFO,
        /** Une étape restante dépend d'une étape bloquée ou en échec. */
        DEPENDENCY_BLOCKED,
        /** Une question ou une demande d'autorisation attend une réponse sur le tour vivant du sujet. */
        QUESTION_PENDING,
        /** Une étape restante est un geste de l'utilisateur, que l'outil ne peut pas faire (SF-219-06). */
        STEP_ACTOR_MOI,
        /** Une étape restante est faite par quelqu'un d'autre (SF-219-06). */
        STEP_ACTOR_TIERS
    }

    /**
     * Une raison.
     *
     * @param code  le code stable
     * @param label la phrase lisible telle quelle
     * @param step  le rang de l'étape concernée (à partir de 1), ou {@code null}
     */
    public record Reason(Code code, String label, Integer step) {
    }

    /** Ce que le calcul lit d'un chantier. */
    public record Input(int number, boolean guided, JourneyPhase phase, String planJson, int planVersion,
                        Integer validatedVersion, Map<String, String> waitsOn, boolean questionPending) {
    }

    /** Vrai si l'attente est encore ouverte (à demander ou demandée). */
    private static boolean open(String status) {
        return "A_FAIRE".equals(status) || "DEMANDE".equals(status);
    }

    /**
     * Le préflight du chantier décrit par {@code in}, avec « avancer jusqu'au blocage » calculé à partir des
     * attentes détaillées ({@code waitInfos} : qui doit agir, à qui) — voir {@link ChantierUnblocker}.
     */
    public static ChantierPreflight evaluate(Input in, Map<String, WaitInfo> waitInfos) {
        ChantierPreflight strict = evaluate(in);
        return new ChantierPreflight(strict.number(), strict.ready(), strict.reasons(), advance(in, waitInfos));
    }

    /** La condition de départ du mode « avancer jusqu'au blocage » (SF-219-05). */
    static Advance advance(Input in, Map<String, WaitInfo> waitInfos) {
        JourneyPlan plan = JourneyPlan.fromJson(in.planJson());
        if (!in.guided() || in.phase() == null || in.phase() == JourneyPhase.CLOS) {
            return new Advance(false, List.of(), List.of(), "Ce chantier n'est pas en mode guidé.");
        }
        if (plan.isEmpty()) {
            return new Advance(false, List.of(), List.of(), "Aucun plan n'est encore posé.");
        }
        if (in.validatedVersion() == null || in.validatedVersion() != in.planVersion()) {
            return new Advance(false, List.of(), List.of(), in.validatedVersion() == null
                    ? "Le plan attend votre validation."
                    : "Le plan a été amendé : la nouvelle version attend votre validation.");
        }
        if (in.phase() != JourneyPhase.EXECUTION && in.phase() != JourneyPhase.VERIFICATION) {
            return new Advance(false, List.of(), List.of(), "Le chantier est en " + in.phase().label() + ".");
        }
        Map<String, WaitInfo> waits = waitInfos;
        if (waits == null) {
            waits = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, String> e : (in.waitsOn() == null ? Map.<String, String>of() : in.waitsOn())
                    .entrySet()) {
                waits.put(e.getKey(), WaitInfo.ofStatus(e.getValue()));
            }
        }
        List<ChantierUnblocker.Mark> marks = ChantierUnblocker.steps(plan, waits);
        List<Integer> tool = new ArrayList<>();
        List<ChantierUnblocker.Mark> remaining = new ArrayList<>();
        boolean left = false;
        for (int i = 0; i < plan.steps().size(); i++) {
            JourneyPlan.Step step = plan.steps().get(i);
            int rank = i + 1;
            if (step.status() == JourneyPlan.StepStatus.VERIFIE) {
                continue;
            }
            left = true;
            if (step.status() == JourneyPlan.StepStatus.FAIT) {
                tool.add(rank); // faite : l'outil la vérifie
                continue;
            }
            ChantierUnblocker.Mark mark = marks.get(i);
            if (mark == null) {
                continue;
            }
            if (mark.who() == ChantierUnblocker.Who.OUTIL) {
                if (step.status() == JourneyPlan.StepStatus.A_FAIRE) {
                    tool.add(rank);
                }
            } else if (mark.step() != null && mark.step() == rank) {
                remaining.add(mark); // une cause première, jamais un héritage
            }
        }
        if (!left) {
            return new Advance(false, List.of(), List.of(), "Toutes les étapes sont vérifiées : rien à exécuter.");
        }
        if (tool.isEmpty()) {
            return new Advance(false, List.of(), List.copyOf(remaining), "Aucune étape n'est à la portée de "
                    + "l'outil : tout ce qui reste attend une décision ou quelqu'un.");
        }
        return new Advance(true, List.copyOf(tool), List.copyOf(remaining), tool.size() + " étape(s) à la portée "
                + "de l'outil" + (remaining.isEmpty() ? "." : " ; " + summary(remaining) + "."));
    }

    /** « 2 décision(s) vous attendent ; on attend Gino » — ce qui restera, en une phrase. */
    public static String summary(List<ChantierUnblocker.Mark> remaining) {
        long mine = remaining.stream().filter(m -> m.who() == ChantierUnblocker.Who.MOI).count();
        List<String> people = remaining.stream()
                .filter(m -> m.who() == ChantierUnblocker.Who.AUTRUI)
                .map(m -> m.waitingOn() == null ? "quelqu'un" : m.waitingOn())
                .distinct()
                .toList();
        List<String> parts = new ArrayList<>();
        if (mine > 0) {
            parts.add(mine + " décision(s) vous attendent");
        }
        if (!people.isEmpty()) {
            parts.add("on attend " + String.join(", ", people));
        }
        return String.join(" ; ", parts);
    }

    /** Le préflight du chantier décrit par {@code in}. */
    public static ChantierPreflight evaluate(Input in) {
        List<Reason> reasons = new ArrayList<>();
        JourneyPlan plan = JourneyPlan.fromJson(in.planJson());
        if (in.questionPending()) {
            reasons.add(new Reason(Code.QUESTION_PENDING,
                    "Une question ou une autorisation attend votre réponse dans ce sujet.", null));
        }
        if (!in.guided() || in.phase() == null || in.phase() == JourneyPhase.CLOS) {
            reasons.add(0, new Reason(Code.NOT_GUIDED, "Ce chantier n'est pas en mode guidé.", null));
            return new ChantierPreflight(in.number(), false, List.copyOf(reasons));
        }
        boolean validatedCurrent = in.validatedVersion() != null && in.validatedVersion() == in.planVersion();
        if (plan.isEmpty() || !validatedCurrent) {
            reasons.add(new Reason(Code.PLAN_NOT_VALIDATED, plan.isEmpty() ? "Aucun plan n'est encore posé."
                    : in.validatedVersion() == null ? "Le plan attend votre validation."
                    : "Le plan a été amendé : la nouvelle version attend votre validation.", null));
        }
        if (in.phase() != JourneyPhase.EXECUTION && in.phase() != JourneyPhase.VERIFICATION) {
            reasons.add(new Reason(Code.NOT_EXECUTING, "Le chantier est en " + in.phase().label()
                    + " : il part en autonome une fois le plan validé.", null));
        }
        if (plan.isEmpty()) {
            return new ChantierPreflight(in.number(), false, List.copyOf(reasons));
        }
        Map<String, String> waits = in.waitsOn() == null ? Map.of() : in.waitsOn();
        boolean remaining = false;
        for (int i = 0; i < plan.steps().size(); i++) {
            JourneyPlan.Step step = plan.steps().get(i);
            int rank = i + 1;
            switch (step.status()) {
                case BLOQUE -> reasons.add(new Reason(Code.STEP_BLOCKED, "Étape " + rank + " bloquée"
                        + (step.evidence() == null ? "." : " : " + step.evidence()), rank));
                case ECHEC -> reasons.add(new Reason(Code.STEP_FAILED, "Étape " + rank + " en échec"
                        + (step.evidence() == null ? "." : " : " + step.evidence()), rank));
                default -> {
                    // rien : faite, vérifiée ou à faire
                }
            }
            if (step.status() == JourneyPlan.StepStatus.VERIFIE) {
                continue;
            }
            remaining = true;
            if (step.status() == JourneyPlan.StepStatus.A_FAIRE && step.waitsOn() != null) {
                String wait = waits.get(step.waitsOn());
                if (wait == null) {
                    reasons.add(new Reason(Code.MISSING_INFO, "Étape " + rank + " : il manque « " + step.waitsOn()
                            + " », et aucune attente ne le suit.", rank));
                } else if (open(wait)) {
                    reasons.add(new Reason(Code.WAIT_OPEN, "Étape " + rank + " : en attente de « " + step.waitsOn()
                            + " » (" + ("DEMANDE".equals(wait) ? "demandé" : "à demander") + ").", rank));
                }
            }
            if (step.status() == JourneyPlan.StepStatus.A_FAIRE
                    && step.effectiveActor() == JourneyPlan.Actor.MOI) {
                reasons.add(new Reason(Code.STEP_ACTOR_MOI, "Étape " + rank + " : « " + step.title()
                        + " » — un geste à faire par vous : l'outil ne peut pas le faire.", rank));
            } else if (step.status() == JourneyPlan.StepStatus.A_FAIRE
                    && step.effectiveActor() == JourneyPlan.Actor.TIERS) {
                reasons.add(new Reason(Code.STEP_ACTOR_TIERS, "Étape " + rank + " : « " + step.title()
                        + " » — à faire par quelqu'un d'autre.", rank));
            }
            for (int dep : step.dependsOn()) {
                JourneyPlan.StepStatus depStatus = plan.steps().get(dep - 1).status();
                if (depStatus == JourneyPlan.StepStatus.BLOQUE || depStatus == JourneyPlan.StepStatus.ECHEC) {
                    reasons.add(new Reason(Code.DEPENDENCY_BLOCKED, "Étape " + rank + " : dépend de l'étape " + dep
                            + ", " + (depStatus == JourneyPlan.StepStatus.BLOQUE ? "bloquée" : "en échec") + ".",
                            rank));
                    break;
                }
            }
        }
        if (!remaining) {
            reasons.add(new Reason(Code.NOTHING_LEFT, "Toutes les étapes sont vérifiées : rien à exécuter.", null));
        }
        return new ChantierPreflight(in.number(), reasons.isEmpty(), List.copyOf(reasons));
    }
}
