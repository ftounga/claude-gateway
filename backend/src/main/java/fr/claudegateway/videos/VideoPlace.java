package fr.claudegateway.videos;

import java.util.UUID;

/**
 * <b>Où se range une vidéo</b> (F-213) : son propriétaire, le sujet (terminal) et le poste du tour.
 *
 * @param userId      propriétaire
 * @param workspaceId sujet (terminal) du tour
 * @param hostId      poste, ou {@code null}
 */
public record VideoPlace(UUID userId, UUID workspaceId, UUID hostId) {
}
