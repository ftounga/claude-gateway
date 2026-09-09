package fr.claudegateway.runner.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.runner.RunnerStatusService.RunnerStatus;

/**
 * Vue de l'état runner exposée à l'utilisateur (F-38 / SF-38-02) : connecté ou non, la dernière
 * activité observée ({@code null} si aucun runner ne s'est jamais signalé), le genre d'interpréteur
 * élu par le runner, et le <b>poste</b> concerné.
 *
 * <p>{@code shell} (F-45 / SF-45-05) vaut {@code posix}, {@code powershell} ou {@code cmd} — la
 * valeur déjà normalisée par le service — ou {@code null} quand aucun runner ne l'a déclarée.
 * L'écran d'appairage s'en sert pour conclure la mise en service en nommant l'interpréteur ; il
 * <b>omet la ligne</b> plutôt que d'écrire « inconnu ».</p>
 *
 * <p>{@code hostId} (F-48 / SF-48-01) est {@code null} pour un projet qui n'est rattaché à aucun
 * poste — l'état d'un projet qu'on vient de créer, que l'écran doit pouvoir dire.</p>
 */
public record RunnerStatusResponse(boolean connected, OffsetDateTime lastSeenAt, String shell,
        UUID hostId) {

    public static RunnerStatusResponse from(RunnerStatus status) {
        return new RunnerStatusResponse(status.connected(), status.lastSeenAt(), status.shell(),
                status.hostId());
    }
}
