package fr.claudegateway.atelier.live;

import java.util.UUID;

/**
 * Les événements <b>adressés à un seul spectateur</b> (F-84 / SF-84-02), par opposition à ceux du
 * tour.
 *
 * <p>Ils ne sont <b>pas</b> mis au tampon et ne consomment aucun numéro d'ordre : ils disent l'état
 * du branchement, pas ce qu'a fait l'agent. Deux vues d'un même tour reçoivent donc toujours la même
 * suite d'événements de tour, même si l'une s'est branchée plus tard que l'autre.</p>
 *
 * <p>Leur JSON est écrit à la main : les seules valeurs qui y entrent sont des identifiants, des
 * nombres et des codes d'erreur connus — rien qui puisse demander un échappement.</p>
 */
public final class TurnAsides {

    /** Numéro d'ordre des apartés : zéro, parce qu'ils n'en ont pas. */
    private static final long NO_SEQ = 0L;

    private TurnAsides() {
    }

    /** « Tu es rebranché » : sur quel tour, à quel curseur, depuis quand. */
    public static TurnEvent attached(UUID turnId, long cursor, long startedAtMs) {
        return new TurnEvent(NO_SEQ, "attached", "{\"turnId\":"
                + (turnId == null ? "null" : "\"" + turnId + "\"")
                + ",\"cursor\":" + cursor + ",\"startedAt\":" + startedAtMs + "}");
    }

    /**
     * « Rien ne tourne ici » — ni sur ce pod, ni chez un pair joignable.
     *
     * <p>C'est la <b>dégradation vers l'état d'origine</b> : avant F-84, un écran qui rouvrait un
     * projet ne voyait rien de vivant. Cet aparté dit exactement cela, et rien de plus — jamais un
     * tour supposé.</p>
     */
    public static TurnEvent idle() {
        return new TurnEvent(NO_SEQ, "idle", "{\"live\":false}");
    }

    /** Une erreur de pré-vol, au format des autres erreurs du flux ({@code {"error": "..."}}). */
    public static TurnEvent error(String code) {
        return new TurnEvent(NO_SEQ, "error", "{\"error\":\"" + code + "\"}");
    }
}
