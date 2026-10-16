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
        OffsetDateTime updatedAt,
        boolean followUpDue,
        TerminalActionCategorie categorie,
        TerminalActionQui qui,
        String subjectName,
        BlockedStep blockedStep,
        UUID mergedIntoId) {

    /**
     * L'étape du plan F-176 que cette attente bloque (F-180 / SF-180-02, D3) : « bloque : étape 3
     * « Déployer » de data-platform ». Calculée à la lecture — le plan peut changer.
     */
    public record BlockedStep(int number, String title, String terminal) {
    }

    /** La même attente, avec son sujet et l'étape qu'elle bloque (tableau). */
    public TerminalActionResponse withContext(String subject, BlockedStep step) {
        return new TerminalActionResponse(id, workspaceId, workspaceName, hostId, subjectId, description, blocks,
                person, kind, status, requestedAt, requestedTo, channel, closedReason, closedAt, proposedStatus,
                proposedReason, proposedAt, createdAt, updatedAt, followUpDue, categorie, qui, subject, step,
                mergedIntoId);
    }

    public static TerminalActionResponse from(TerminalAction action) {
        return from(action, null, false);
    }

    public static TerminalActionResponse from(TerminalAction action, String workspaceName) {
        return from(action, workspaceName, false);
    }

    /** Avec l'indication « à relancer » (F-175 / SF-175-06), calculée par {@link TerminalActionFollowUp}. */
    public static TerminalActionResponse from(TerminalAction action, String workspaceName, boolean followUpDue) {
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
                action.getUpdatedAt(),
                followUpDue,
                action.getCategorie(),
                action.getQui(),
                null,
                null,
                action.getMergedIntoId());
    }
}
