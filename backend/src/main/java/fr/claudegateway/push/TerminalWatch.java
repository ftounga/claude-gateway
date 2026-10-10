package fr.claudegateway.push;

import java.util.UUID;

/**
 * <b>Ce terminal est-il regardé ?</b> (F-185 / SF-185-03) — la question que l'émetteur pose avant
 * d'envoyer (D7 de F-153). Une interface plutôt qu'une dépendance au registre des terminaux : le
 * push n'a pas à connaître les places ni leur plafond.
 */
public interface TerminalWatch {

    /** Vrai si ce terminal de ce compte est regardé en ce moment. Lu par user_id ET workspace_id. */
    boolean watching(UUID userId, UUID workspaceId);

    /** Personne ne regarde jamais : l'émetteur envoie toujours (tests, contexte sans registre). */
    TerminalWatch NONE = (userId, workspaceId) -> false;
}
