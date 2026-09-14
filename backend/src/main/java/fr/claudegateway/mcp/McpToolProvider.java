package fr.claudegateway.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * Fournit une spécification d'outil MCP à enregistrer sur le serveur. Tout composant Spring
 * implémentant cette interface voit son outil découvert et exposé par {@code McpServerConfig}.
 *
 * <p>Chaque outil résout son identité et son cloisonnement depuis {@link McpCallContext} (jamais un
 * paramètre d'entrée), porte ses annotations et son schéma, et suit les gardes du cadrage F-112 §6.
 * En SF-112-01, seul {@code session_info} est fourni ; les outils de domaine arrivent en
 * SF-112-04→07.</p>
 */
public interface McpToolProvider {

    /** La spécification (outil + gestionnaire d'appel) à enregistrer sur le serveur MCP. */
    SyncToolSpecification specification();
}
