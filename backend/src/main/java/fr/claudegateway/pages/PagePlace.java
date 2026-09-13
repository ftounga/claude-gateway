package fr.claudegateway.pages;

import java.util.Objects;
import java.util.UUID;

/**
 * <b>Le lieu</b> d'une page (F-109 / SF-109-01, cadrage §5) : à qui elle est, dans quel espace, sur quel
 * poste ou client, et depuis quel projet ou terminal elle a été publiée.
 *
 * @param userId      propriétaire — toujours l'utilisateur du tour ou du contexte de sécurité, jamais un
 *                    paramètre client
 * @param space       espace de rangement
 * @param hostId      poste (Forge) ou client (Vigie) ; {@code null} pour un projet sans poste
 * @param workspaceId projet ou terminal d'origine ; {@code null} admis
 */
public record PagePlace(UUID userId, PageSpace space, UUID hostId, UUID workspaceId) {

    public PagePlace {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(space, "space");
    }
}
