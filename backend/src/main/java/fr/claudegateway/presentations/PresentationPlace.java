package fr.claudegateway.presentations;

import java.util.Objects;
import java.util.UUID;

/**
 * <b>Le lieu</b> d'une présentation (F-129 / SF-129-02) : à qui elle est, dans quel espace, sur quel
 * poste ou client, depuis quel projet. Même forme que {@code PagePlace} (F-109).
 *
 * @param userId      propriétaire — toujours l'utilisateur du tour, jamais un paramètre client
 * @param space       espace de rangement (Forge/Vigie)
 * @param hostId      poste ou client ; {@code null} pour un projet sans poste
 * @param workspaceId projet ou terminal d'origine ; {@code null} admis
 */
public record PresentationPlace(UUID userId, PresentationSpace space, UUID hostId, UUID workspaceId) {

    public PresentationPlace {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(space, "space");
    }
}
