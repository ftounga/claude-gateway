package fr.claudegateway.runner;

import java.util.UUID;

/**
 * Identité d'un runner authentifié par jeton (F-38). Second type de porteur d'identité de la
 * plateforme, distinct de l'{@code AuthenticatedUser} (JWT utilisateur) : il n'est jamais posé dans
 * le {@code SecurityContext} de la chaîne principale et ne transite que par la chaîne dédiée
 * {@code /runner/**}. Consommé par le canal WebSocket (SF-38-02).
 *
 * <p>Depuis F-48 / SF-48-01, un runner est rattaché à un <b>poste</b> et non plus à un projet : une
 * machine, une racine, un appairage. Le <b>projet</b> ne fait donc plus partie de l'identité — il
 * voyage <b>par appel</b>, dans la trame {@code tool_call}, et c'est le dossier où le runner fait
 * démarrer le tour (SF-48-02 ; ce n'est plus une borne depuis F-73 / SF-73-01).</p>
 */
public record RunnerIdentity(UUID tokenId, UUID userId, UUID hostId) {
}
