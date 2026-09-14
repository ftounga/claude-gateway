package fr.claudegateway.mcp;

/** Origine de l'authentification d'un appel MCP (F-112). */
public enum McpAuthKind {
    /** Jeton d'accès OAuth 2.1 (SF-112-02). */
    OAUTH,
    /** Jeton personnel haché (SF-112-03). */
    PERSONAL
}
