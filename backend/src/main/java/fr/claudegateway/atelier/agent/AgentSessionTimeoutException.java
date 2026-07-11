package fr.claudegateway.atelier.agent;

/**
 * Délai d'attente dépassé sur la complétion d'une session Managed Agents (F-28 / Phase 2) : l'état
 * {@code session.status_idle} n'a pas été atteint dans les bornes (timeout dur ou nombre max de
 * tours de polling). Garde-fou de coût runtime : la session est terminée en {@code finally}.
 */
public class AgentSessionTimeoutException extends RuntimeException {

    public AgentSessionTimeoutException(String message) {
        super(message);
    }
}
