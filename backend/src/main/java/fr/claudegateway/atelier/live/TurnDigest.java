package fr.claudegateway.atelier.live;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Le résumé d'un tour</b>, tenu par le tour lui-même (F-191 / SF-191-01, le fil des tâches).
 *
 * <p>Ce que la bande au-dessus de la saisie montre d'un tour qui tourne ailleurs : la demande, l'étape
 * du plan, l'outil courant, l'attente d'une décision, les sous-agents. <b>Rien n'est calculé de
 * neuf</b> : le résumé est nourri par les événements que le tour publie déjà ({@code action},
 * {@code plan}, {@code confirm_*}, {@code question_*}, {@code done}, {@code error}). Aucun appel
 * modèle, aucune écriture en base.</p>
 *
 * <p>Borné : demande, étape et libellés tronqués, {@value #MAX_SUBAGENTS} sous-agents au plus. Le
 * contenu n'est jamais journalisé.</p>
 */
public final class TurnDigest {

    static final int MAX_DEMAND_CHARS = 160;
    static final int MAX_STEP_CHARS = 120;
    static final int MAX_LABEL_CHARS = 80;
    static final int MAX_SUBAGENTS = 6;

    /** Les événements qui nourrissent le résumé — les autres ne sont même pas relus. */
    static final Set<String> WATCHED = Set.of("action", "plan", LiveTurn.CONFIRM_REQUEST,
            LiveTurn.CONFIRM_RESOLVED, LiveTurn.QUESTION_REQUEST, LiveTurn.QUESTION_RESOLVED, "done",
            "error");

    /** Les outils de délégation : chacun ouvre un sous-agent. */
    private static final Set<String> DELEGATIONS = Set.of("task", "explore");

    private String demand;
    private int planDone;
    private int planTotal;
    private String planStep;
    private String tool;
    private String waiting;
    private String outcome;
    private final Deque<SubAgent> subagents = new ArrayDeque<>();

    /** Un sous-agent : une délégation ({@code task}, {@code explore}) et son état. */
    public record SubAgent(String kind, String label, String state, long startedAt) {
    }

    /** Vrai si cet événement nourrit le résumé. */
    static boolean watches(String name) {
        return name != null && WATCHED.contains(name);
    }

    /** La demande du tour, telle que l'utilisateur l'a envoyée (tronquée). */
    public synchronized void demand(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        this.demand = truncate(text.strip().replaceAll("\\s+", " "), MAX_DEMAND_CHARS);
    }

    /** Lit un événement publié par le tour. Une charge utile inattendue est ignorée, jamais fatale. */
    synchronized void observe(String name, JsonNode payload, long nowMs) {
        if (name == null || payload == null) {
            return;
        }
        switch (name) {
            case "action" -> onAction(payload, nowMs);
            case "plan" -> onPlan(payload);
            case LiveTurn.CONFIRM_REQUEST -> waiting = "approval";
            case LiveTurn.QUESTION_REQUEST -> waiting = "question";
            case LiveTurn.CONFIRM_RESOLVED, LiveTurn.QUESTION_RESOLVED -> waiting = null;
            case "done" -> {
                waiting = null;
                if (outcome == null) {
                    outcome = payload.path("interrupted").asBoolean(false) ? "interrupted" : "done";
                } else if ("done".equals(outcome) && payload.path("interrupted").asBoolean(false)) {
                    outcome = "interrupted";
                }
            }
            case "error" -> {
                waiting = null;
                outcome = "failed";
            }
            default -> {
                // Événement non suivi : rien à retenir.
            }
        }
    }

    private void onAction(JsonNode payload, long nowMs) {
        // Deux formes publiées : la boucle de conversation ({type, path}) et l'exécution Managed
        // Agent ({tool, toolUseId, detail}).
        String type = text(payload, "type");
        if (type == null) {
            type = text(payload, "tool");
        }
        if (type == null) {
            return;
        }
        tool = truncate(type, 40);
        if (DELEGATIONS.contains(type)) {
            String label = text(payload, "path");
            if (label == null) {
                label = text(payload, "detail");
            }
            subagents.addLast(new SubAgent(type,
                    label == null ? null : truncate(label.strip().replaceAll("\\s+", " "), MAX_LABEL_CHARS),
                    "running", nowMs));
            while (subagents.size() > MAX_SUBAGENTS) {
                subagents.removeFirst();
            }
            return;
        }
        // Une action non déléguée : la boucle principale a repris la main, les délégations sont finies.
        closeSubagents();
    }

    private void onPlan(JsonNode payload) {
        JsonNode steps = payload.path("steps");
        if (!steps.isArray()) {
            return;
        }
        int done = 0;
        String active = null;
        for (JsonNode step : steps) {
            String status = step.path("status").asText("");
            if ("done".equals(status)) {
                done++;
            } else if ("active".equals(status) && active == null) {
                active = text(step, "title");
            }
        }
        planTotal = steps.size();
        planDone = done;
        planStep = active == null ? null : truncate(active, MAX_STEP_CHARS);
    }

    private void closeSubagents() {
        if (subagents.stream().noneMatch(s -> "running".equals(s.state()))) {
            return;
        }
        List<SubAgent> closed = new ArrayList<>(subagents.size());
        for (SubAgent s : subagents) {
            closed.add("running".equals(s.state())
                    ? new SubAgent(s.kind(), s.label(), "done", s.startedAt())
                    : s);
        }
        subagents.clear();
        subagents.addAll(closed);
    }

    synchronized String demand() {
        return demand;
    }

    synchronized int planDone() {
        return planDone;
    }

    synchronized int planTotal() {
        return planTotal;
    }

    synchronized String planStep() {
        return planStep;
    }

    synchronized String tool() {
        return tool;
    }

    synchronized String waiting() {
        return waiting;
    }

    synchronized String outcome() {
        return outcome;
    }

    /** Les sous-agents ; à la fin du tour, tous sont dits terminés. */
    synchronized List<SubAgent> subagents(boolean finished) {
        if (finished) {
            closeSubagents();
        }
        return List.copyOf(subagents);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String s = value.asText(null);
        return s == null || s.isBlank() ? null : s;
    }

    static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }
}
