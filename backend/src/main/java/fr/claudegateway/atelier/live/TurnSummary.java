package fr.claudegateway.atelier.live;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * <b>L'instantané du résumé d'un tour</b> (F-191 / SF-191-01) : ce qui voyage du registre (ou d'un pod
 * pair) jusqu'à la bande des tâches.
 *
 * <p>Tolérant aux champs inconnus : pendant une bascule progressive, un pod plus récent peut en
 * envoyer davantage à un pod plus ancien.</p>
 *
 * @param state     {@code waiting}, {@code running}, {@code done}, {@code failed} ou {@code interrupted}
 * @param waiting   {@code approval}, {@code question} ou {@code null}
 * @param endedAt   instant de fin (ms epoch), {@code null} tant que le tour tourne
 * @param chantier  le fil du tour (F-221 / SF-221-01) : le chantier, {@code null} = fil commun
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TurnSummary(UUID turnId, UUID userId, UUID workspaceId, String state, String waiting,
        String demand, long startedAt, Long endedAt, int planDone, int planTotal, String planStep,
        String tool, List<TurnDigest.SubAgent> subagents, Integer chantier) {

    public static final String WAITING = "waiting";
    public static final String RUNNING = "running";

    public TurnSummary {
        subagents = subagents == null ? List.of() : List.copyOf(subagents);
    }

    /** Vrai tant que le tour tourne (en cours ou en attente d'une décision). */
    public boolean live() {
        return WAITING.equals(state) || RUNNING.equals(state);
    }
}
