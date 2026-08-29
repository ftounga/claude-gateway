package fr.claudegateway.runner.channel;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Connexion runner enregistrée dans le {@link RunnerRegistry} (F-38 / SF-38-02). Elle décrit
 * <b>quel</b> runner est présent pour un workspace (identité issue du jeton, SF-38-01) et sur
 * <b>quel</b> nœud (replica) sa socket vit. Le transport effectif des messages d'outil vers cette
 * connexion viendra en SF-38-05 ; ici on ne tient que la présence.
 *
 * @param workspaceId workspace auquel le runner est rattaché (clé du registre)
 * @param userId      propriétaire (isolation multi-tenant)
 * @param tokenId     jeton runner ayant ouvert la connexion
 * @param nodeId      identifiant du replica qui héberge la socket vivante
 * @param connectedAt instant d'établissement
 */
public record RunnerConnection(
        UUID workspaceId,
        UUID userId,
        UUID tokenId,
        String nodeId,
        OffsetDateTime connectedAt) {
}
