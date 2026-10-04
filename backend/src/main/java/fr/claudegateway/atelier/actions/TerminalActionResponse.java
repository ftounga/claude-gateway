package fr.claudegateway.atelier.actions;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Une attente telle que l'écran la lit (F-154 / SF-154-01, F-175 / SF-175-01).
 *
 * <p>Porte tout ce que le panneau affiche : ce qu'il faut faire, ce que ça débloque, qui est
 * concerné, depuis quand, à qui et par où la demande est partie — et, pour une attente fermée,
 * <b>pourquoi</b>. {@code workspaceName} n'est rempli que dans le tableau du poste, pour dire de quel
 * terminal l'attente est née.</p>
 */
public record TerminalActionResponse(
        UUID id,
        UUID workspaceId,
        String workspaceName,
        UUID hostId,
        UUID subjectId,
        String description,
        String blocks,
        String person,
        TerminalActionKind kind,
        TerminalActionStatus status,
        OffsetDateTime requestedAt,
        String requestedTo,
        String channel,
        String closedReason,
        OffsetDateTime closedAt,
        TerminalActionStatus proposedStatus,
        String proposedReason,
        OffsetDateTime proposedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static TerminalActionResponse from(TerminalAction action) {
        return from(action, null);
    }

    public static TerminalActionResponse from(TerminalAction action, String workspaceName) {
        return new TerminalActionResponse(
                action.getId(),
                action.getWorkspaceId(),
                workspaceName,
                action.getHostId(),
                action.getSubjectId(),
                action.getDescription(),
                action.getBlocks(),
                action.getPerson(),
                action.getKind(),
                action.getStatus(),
                action.getRequestedAt(),
                action.getRequestedTo(),
                action.getChannel(),
                action.getClosedReason(),
                action.getClosedAt(),
                action.getProposedStatus(),
                action.getProposedReason(),
                action.getProposedAt(),
                action.getCreatedAt(),
                action.getUpdatedAt());
    }
}
