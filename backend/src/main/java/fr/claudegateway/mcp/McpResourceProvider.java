package fr.claudegateway.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;

/**
 * Fournit une <b>ressource MCP</b> (lecture par référence, cadrage F-112 §5) à enregistrer sur le
 * serveur. Tout composant Spring implémentant cette interface est découvert par
 * {@code McpServerConfig}.
 *
 * <p>Comme les outils, une ressource résout son identité et son cloisonnement depuis
 * {@link McpCallContext} (jamais un paramètre), applique le filtre de secrets, et marque non fiable
 * tout contenu tiers.</p>
 */
public interface McpResourceProvider {

    /** La spécification (ressource + gestionnaire de lecture) à enregistrer. */
    SyncResourceSpecification specification();
}
