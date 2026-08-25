package fr.claudegateway.atelier.agent;

/**
 * Accès à un serveur <b>MCP</b> pour une session (F-31 / SF-31-05, ADR-015), exprimé <b>dans le
 * domaine</b> : le service dit « cette session parle à ce serveur, avec les credentials de ce vault »,
 * le provider seul sait comment le fournisseur l'exprime (Provider Independence).
 *
 * <p>Le vault est attaché <b>à la création</b> de la session : le fournisseur ne permet pas d'en
 * ajouter un ensuite. Une session ouverte sans vault ne pourra donc jamais s'authentifier auprès du
 * serveur MCP — il faut en rouvrir une.</p>
 *
 * <p>Ce record ne porte <b>aucun secret</b> : le jeton vit dans le vault, chez le fournisseur, et
 * n'entre jamais dans le conteneur (proxy MCP côté fournisseur).</p>
 *
 * @param vaultId    identifiant du vault de credentials à attacher à la session
 * @param serverName nom du serveur MCP dans la configuration de session, référencé par le toolset
 * @param serverUrl  URL du serveur MCP (transport HTTP streamable) ; c'est aussi la clé de la
 *                   credential dans le vault
 */
public record McpAccess(String vaultId, String serverName, String serverUrl) {
}
