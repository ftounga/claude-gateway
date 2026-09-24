package fr.claudegateway.atelier.actions;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Une action ouverte d'un <b>autre</b> projet, telle que la section « Ailleurs » du menu la lit
 * (F-154 / SF-154-03) : elle porte le <b>nom du projet</b>, sans quoi elle serait illisible.
 *
 * <p>Lecture seule : on la voit, on ne la traite pas d'ici — la traiter demande le contexte de son
 * terminal.</p>
 */
public record TerminalActionElsewhereResponse(
        UUID id,
        UUID workspaceId,
        String workspaceName,
        String description,
        String blocks,
        String person,
        TerminalActionKind kind,
        OffsetDateTime createdAt) {

    public static TerminalActionElsewhereResponse from(TerminalAction action, String workspaceName) {
        return new TerminalActionElsewhereResponse(
                action.getId(),
                action.getWorkspaceId(),
                workspaceName,
                action.getDescription(),
                action.getBlocks(),
                action.getPerson(),
                action.getKind(),
                action.getCreatedAt());
    }
}
