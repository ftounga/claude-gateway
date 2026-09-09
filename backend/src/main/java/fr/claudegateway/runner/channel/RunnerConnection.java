package fr.claudegateway.runner.channel;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Connexion runner enregistrée dans le {@link RunnerRegistry} (F-38 / SF-38-02). Elle décrit
 * <b>quel</b> runner est présent pour un <b>poste</b> (identité issue du jeton, SF-38-01) et sur
 * <b>quel</b> nœud (replica) sa socket vit. Ici on ne tient que la présence.
 *
 * <p>Depuis F-48 / SF-48-01, la clef est le poste et non plus le projet : une machine n'ouvre
 * qu'une liaison, et tous ses projets l'empruntent.</p>
 *
 * @param hostId      poste auquel le runner est rattaché (clé du registre)
 * @param userId      propriétaire (isolation multi-tenant)
 * @param tokenId     jeton runner ayant ouvert la connexion
 * @param nodeId      identifiant du replica qui héberge la socket vivante
 * @param connectedAt instant d'établissement
 */
public record RunnerConnection(
        UUID hostId,
        UUID userId,
        UUID tokenId,
        String nodeId,
        OffsetDateTime connectedAt) {
}
