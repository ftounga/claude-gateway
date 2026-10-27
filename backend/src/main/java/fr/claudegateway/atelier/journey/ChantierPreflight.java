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
 */
public record ChantierPreflight(int number, boolean ready, List<Reason> reasons) {

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
        QUESTION_PENDING
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
