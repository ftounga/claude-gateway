package fr.claudegateway.images;

import java.util.Objects;
import java.util.UUID;

/**
 * <b>Le lieu</b> d'une image générée (F-142 / SF-142-04) : à qui elle est, dans quel espace, sur quel
 * poste ou client, et depuis quel projet/terminal elle a été demandée. Même patron que {@code PagePlace}
 * et {@code PresentationPlace}.
 *
 * @param userId      propriétaire — toujours l'utilisateur du tour, jamais un paramètre client
 * @param space       espace de rangement
 * @param hostId      poste (Forge) ou client (Vigie) ; {@code null} pour un projet sans poste
 * @param workspaceId projet ou terminal d'origine ; {@code null} admis
 */
public record ImagePlace(UUID userId, ImageSpace space, UUID hostId, UUID workspaceId) {

    public ImagePlace {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(space, "space");
    }
}
