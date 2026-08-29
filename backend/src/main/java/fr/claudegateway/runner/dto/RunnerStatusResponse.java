package fr.claudegateway.runner.dto;

import java.time.OffsetDateTime;

import fr.claudegateway.runner.RunnerStatusService.RunnerStatus;

/**
 * Vue de l'état runner d'un workspace (F-38 / SF-38-02) exposée à l'utilisateur : connecté ou non, et
 * la dernière activité observée ({@code null} si aucun runner ne s'est jamais signalé).
 */
public record RunnerStatusResponse(boolean connected, OffsetDateTime lastSeenAt) {

    public static RunnerStatusResponse from(RunnerStatus status) {
        return new RunnerStatusResponse(status.connected(), status.lastSeenAt());
    }
}
