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
        UUID mergedIntoId,
        UUID interlocuteurId,
        String interlocuteurName,
        java.time.LocalDate dueAt,
        String dueState,
        Integer chantierNumber,
        String proposalSource,
        OffsetDateTime lastGestureAt) {

    /** Le jour de référence de l'échéance : Paris, comme le récapitulatif de 9 h. */
    static final java.time.ZoneId ZONE = java.time.ZoneId.of("Europe/Paris");

    /**
     * L'état de l'échéance d'une attente <b>ouverte</b> (F-180 / SF-180-06) : {@code NONE}, {@code LATER},
     * {@code TODAY}, {@code OVERDUE}. Une attente fermée n'a plus d'échéance qui compte : {@code NONE}.
     */
    public static String dueStateOf(TerminalAction action, java.time.LocalDate today) {
        if (action.getDueAt() == null || !action.isOpen()) {
            return "NONE";
        }
        if (action.getDueAt().isBefore(today)) {
            return "OVERDUE";
        }
        return action.getDueAt().isEqual(today) ? "TODAY" : "LATER";
    }

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
                mergedIntoId, interlocuteurId, interlocuteurName, dueAt, dueState, chantierNumber, proposalSource,
                lastGestureAt);
    }

    /** La même attente, avec le nom de son interlocuteur normalisé (F-180 / SF-180-03). */
    public TerminalActionResponse withInterlocuteur(String name) {
        return new TerminalActionResponse(id, workspaceId, workspaceName, hostId, subjectId, description, blocks,
                person, kind, status, requestedAt, requestedTo, channel, closedReason, closedAt, proposedStatus,
                proposedReason, proposedAt, createdAt, updatedAt, followUpDue, categorie, qui, subjectName,
                blockedStep, mergedIntoId, interlocuteurId, name, dueAt, dueState, chantierNumber, proposalSource,
                lastGestureAt);
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
                action.getMergedIntoId(),
                action.getInterlocuteurId(),
                null,
                action.getDueAt(),
                dueStateOf(action, java.time.LocalDate.now(ZONE)),
                action.getChantierNumber(),
                action.getProposalSource(),
                action.lastGestureOrCreated());
    }
}
