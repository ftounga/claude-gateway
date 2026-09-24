package fr.claudegateway.atelier.actions;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Une action telle que l'écran la lit (F-154 / SF-154-01).
 *
 * <p>Porte tout ce que le menu affiche : ce qu'il faut faire, ce que ça débloque, qui est concerné,
 * depuis quand — et, pour une action fermée, <b>pourquoi</b>.</p>
 */
public record TerminalActionResponse(
        UUID id,
        UUID workspaceId,
        UUID subjectId,
        String description,
        String blocks,
        String person,
        TerminalActionKind kind,
        TerminalActionStatus status,
        String closedReason,
        OffsetDateTime closedAt,
        OffsetDateTime createdAt) {

    public static TerminalActionResponse from(TerminalAction action) {
        return new TerminalActionResponse(
                action.getId(),
                action.getWorkspaceId(),
                action.getSubjectId(),
                action.getDescription(),
                action.getBlocks(),
                action.getPerson(),
                action.getKind(),
                action.getStatus(),
                action.getClosedReason(),
                action.getClosedAt(),
                action.getCreatedAt());
    }
}
