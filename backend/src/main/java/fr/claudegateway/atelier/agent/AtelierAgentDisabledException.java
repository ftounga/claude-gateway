package fr.claudegateway.atelier.agent;

/**
 * Tentative de run d'atelier alors que la Phase 2 Managed Agents est désactivée
 * (flag {@code app.atelier.agent.enabled} faux). Levée <b>avant tout appel réseau</b> : aucune
 * session n'est créée, aucun coût runtime engagé.
 */
public class AtelierAgentDisabledException extends IllegalStateException {

    public AtelierAgentDisabledException(String message) {
        super(message);
    }
}
