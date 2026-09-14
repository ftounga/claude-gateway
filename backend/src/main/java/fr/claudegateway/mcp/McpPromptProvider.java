package fr.claudegateway.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;

/**
 * Fournit un <b>prompt MCP</b> (gabarit proposé à l'utilisateur dans son IA, cadrage F-112 §5) à
 * enregistrer sur le serveur. Tout composant Spring implémentant cette interface est découvert par
 * {@code McpServerConfig}.
 *
 * <p>Un prompt est un gabarit de <b>texte</b> : il n'exécute rien, il propose une formulation qui
 * s'appuie sur les outils. Il ne contient jamais de contenu tiers exécutable.</p>
 */
public interface McpPromptProvider {

    /** La spécification (prompt + gestionnaire) à enregistrer. */
    SyncPromptSpecification specification();
}
