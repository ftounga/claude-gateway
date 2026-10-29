package fr.claudegateway.atelier.journey;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * <b>Qui débloque ?</b> (F-220 / SF-220-01) — pour un chantier ouvert et chacune de ses étapes non faites : qui
 * doit agir pour qu'il avance. Trois réponses, mutuellement exclusives : <b>l'outil</b> (rien n'attend personne),
 * <b>l'utilisateur</b> (une décision qu'il peut prendre tout de suite), <b>quelqu'un d'autre</b> (une attente
 * envers un tiers).
 *
 * <p><b>Calculé, jamais deviné</b> : tout se lit dans le plan persisté, l'état des attentes F-175 (avec leur
 * {@code qui}) et l'état du tour vivant. Calcul pur — aucune dépendance, aucun accès aux données : l'appelant a
 * déjà vérifié la propriété du terminal. SF-219-06 : l'<b>acteur</b> de l'étape ({@code MOI}, {@code TIERS}) est lu
 * comme une attente propre à l'étape. La règle est écrite dans
 * {@code docs/features/F-220/CADRAGE-F-220-tableau-de-bord-client.md}.</p>
 */
public final class ChantierUnblocker {

    private ChantierUnblocker() {
    }

    /** Qui doit agir. */
    public enum Who {
        /** Rien n'attend personne : la suite est technique et faisable dans l'application. */
        OUTIL("L'outil peut avancer"),
        /** Une décision que l'utilisateur peut prendre tout de suite. */
        MOI("Ça attend votre décision"),
        /** Une action externe : un message, une information, un accès, une réponse d'un tiers. */
        AUTRUI("On attend quelqu'un");

        private final String label;

        Who(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Une marque « qui débloque ».
     *
     * @param who       qui doit agir
     * @param label     le libellé court de la pastille
     * @param detail    la phrase lisible telle quelle
     * @param step      le rang de l'étape concernée (à partir de 1), ou {@code null}
     * @param waitingOn à qui (seulement pour {@link Who#AUTRUI}), ou {@code null}
     * @param since     depuis quand (seulement pour une attente), ou {@code null}
     */
    public record Mark(Who who, String label, String detail, Integer step, String waitingOn, OffsetDateTime since) {

        static Mark of(Who who, String detail, Integer step) {
            return new Mark(who, who.label(), detail, step, null, null);
        }

        static Mark waiting(Who who, String detail, Integer step, String waitingOn, OffsetDateTime since) {
            return new Mark(who, who.label(), detail, step, waitingOn, since);
        }

        /** La même marque, pour une étape qui attend celle-ci. */
        Mark inheritedBy(int rank) {
            return new Mark(who, label, "Étape " + rank + " : attend l'étape " + step + " — " + detail, step,
                    waitingOn, since);
        }
    }

    /** Ce que le calcul lit d'un chantier ouvert. */
    public record Input(boolean guided, JourneyPhase phase, String planJson, int planVersion,
                        Integer validatedVersion, boolean diagnosisPending, boolean closeProposed,
                        boolean questionPending, Map<String, WaitInfo> waits) {
    }

    /**
     * La marque de chaque étape, dans l'ordre du plan ; {@code null} pour une étape faite ou vérifiée.
     *
     * @param waits les attentes par clé ({@code null} = aucune connue)
     */
    public static List<Mark> steps(JourneyPlan plan, Map<String, WaitInfo> waits) {
        Map<String, WaitInfo> known = waits == null ? Map.of() : waits;
        List<Mark> marks = new ArrayList<>(plan.steps().size());
        for (int i = 0; i < plan.steps().size(); i++) {
            marks.add(step(plan, i, known, marks));
        }
        return Collections.unmodifiableList(marks);
    }

    private static Mark step(JourneyPlan plan, int index, Map<String, WaitInfo> waits, List<Mark> before) {
        JourneyPlan.Step s = plan.steps().get(index);
        int rank = index + 1;
        if (s.isDone()) {
            return null;
        }
        WaitInfo wait = s.waitsOn() == null ? null : waits.get(s.waitsOn());
        if (s.status() == JourneyPlan.StepStatus.BLOQUE || s.status() == JourneyPlan.StepStatus.ECHEC) {
            if (wait != null && wait.open() && !wait.mine()) {
                return waitMark(s, rank, wait);
            }
            String state = s.status() == JourneyPlan.StepStatus.BLOQUE ? "bloquée" : "en échec";
            return Mark.of(Who.MOI, "Étape " + rank + " " + state
                    + (s.evidence() == null ? "." : " : " + s.evidence()), rank);
        }
        if (s.waitsOn() != null) {
            if (wait == null) {
                return Mark.waiting(Who.AUTRUI, "Étape " + rank + " : il manque « " + s.waitsOn()
                        + " », et personne ne le suit.", rank, null, null);
            }
            if (wait.open()) {
                return waitMark(s, rank, wait);
            }
        }
        // SF-219-06 : un geste que l'outil ne peut pas faire est sa propre cause première (avant l'héritage).
        if (s.effectiveActor() == JourneyPlan.Actor.MOI) {
            return Mark.of(Who.MOI, "Étape " + rank + " : « " + s.title() + " » — un geste à faire par vous : "
                    + "l'outil ne peut pas le faire.", rank);
        }
        if (s.effectiveActor() == JourneyPlan.Actor.TIERS) {
            return Mark.waiting(Who.AUTRUI, "Étape " + rank + " : « " + s.title() + " » — à faire par quelqu'un "
                    + "d'autre.", rank, null, null);
        }
        for (int blocking : plan.blockingSteps(index)) {
            Mark upstream = blocking >= 1 && blocking <= before.size() ? before.get(blocking - 1) : null;
            if (upstream != null && upstream.who() != Who.OUTIL) {
                // Une marque héritée pointe toujours sa cause première (step = rang de l'étape qui bloque).
                Mark root = upstream.step() != null && upstream.step() >= 1 && upstream.step() <= before.size()
                        && before.get(upstream.step() - 1) != null ? before.get(upstream.step() - 1) : upstream;
                return root.inheritedBy(rank);
            }
        }
        return Mark.of(Who.OUTIL, "Étape " + rank + " : « " + s.title() + " ».", rank);
    }

    private static Mark waitMark(JourneyPlan.Step s, int rank, WaitInfo wait) {
        String state = "DEMANDE".equals(wait.status()) ? "demandé" : "à demander";
        if (wait.mine()) {
            return Mark.waiting(Who.MOI, "Étape " + rank + " : « " + s.waitsOn() + " » est un geste à faire par vous ("
                    + state + ").", rank, null, wait.since());
        }
        return Mark.waiting(Who.AUTRUI, "Étape " + rank + " : en attente de « " + s.waitsOn() + " »"
                + (wait.who() == null ? "" : " auprès de " + wait.who()) + " (" + state + ").", rank, wait.who(),
                wait.since());
    }

    /** La marque du chantier décrit par {@code in} — jamais {@code null}. */
    public static Mark chantier(Input in) {
        JourneyPlan plan = JourneyPlan.fromJson(in.planJson());
        if (in.questionPending()) {
            return Mark.of(Who.MOI, "Une question ou une autorisation attend votre réponse dans ce sujet.", null);
        }
        if (in.closeProposed()) {
            return Mark.of(Who.MOI, "La clôture du chantier est proposée : à confirmer.", null);
        }
        if (in.diagnosisPending()) {
            return Mark.of(Who.MOI, "Le diagnostic est posé : « Prêt à planifier » attend votre feu vert.", null);
        }
        boolean current = in.validatedVersion() != null && in.validatedVersion() == in.planVersion();
        if (!plan.isEmpty() && !current && in.phase() != JourneyPhase.INVESTIGATION) {
            return Mark.of(Who.MOI, in.validatedVersion() == null ? "Le plan attend votre validation."
                    : "Le plan a été amendé : la nouvelle version attend votre validation.", null);
        }
        if (!in.guided() || in.phase() == null || in.phase() == JourneyPhase.INVESTIGATION) {
            return Mark.of(Who.OUTIL, "L'agent investigue : il avance seul jusqu'au diagnostic.", null);
        }
        if (plan.isEmpty()) {
            return Mark.of(Who.OUTIL, "L'agent rédige le plan.", null);
        }
        List<Mark> marks = steps(plan, in.waits());
        Mark firstMine = null;
        Mark firstOther = null;
        boolean remaining = false;
        for (int i = 0; i < plan.steps().size(); i++) {
            JourneyPlan.Step s = plan.steps().get(i);
            if (s.status() == JourneyPlan.StepStatus.VERIFIE) {
                continue;
            }
            remaining = true;
            if (s.status() == JourneyPlan.StepStatus.FAIT) {
                return Mark.of(Who.OUTIL, "Étape " + (i + 1) + " faite : l'outil la vérifie.", i + 1);
            }
            Mark mark = marks.get(i);
            if (mark.who() == Who.OUTIL) {
                return mark;
            }
            boolean own = mark.step() != null && mark.step() == i + 1; // pas héritée d'une étape antérieure
            if (own && mark.who() == Who.MOI && firstMine == null) {
                firstMine = mark;
            } else if (own && mark.who() == Who.AUTRUI && firstOther == null) {
                firstOther = mark;
            }
        }
        if (!remaining) {
            return Mark.of(Who.MOI, "Toutes les étapes sont vérifiées : le chantier est à clore.", null);
        }
        if (firstMine != null) {
            return firstMine;
        }
        if (firstOther != null) {
            return firstOther;
        }
        // Que des héritages : la première étape restante dit déjà qui attend qui.
        for (Mark mark : marks) {
            if (mark != null) {
                return mark;
            }
        }
        return Mark.of(Who.OUTIL, "L'outil peut avancer.", null);
    }
}
