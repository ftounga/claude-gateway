package fr.claudegateway.runner.dto;

import java.time.OffsetDateTime;

import fr.claudegateway.runner.RunnerStatusService.RunnerStatus;

/**
 * Vue de l'état runner d'un workspace (F-38 / SF-38-02) exposée à l'utilisateur : connecté ou non, la
 * dernière activité observée ({@code null} si aucun runner ne s'est jamais signalé), et le genre
 * d'interpréteur élu par le runner.
 *
 * <p>{@code shell} (F-45 / SF-45-05) vaut {@code posix}, {@code powershell} ou {@code cmd} — la
 * valeur déjà normalisée par le service — ou {@code null} quand aucun runner ne l'a déclarée (runner
 * antérieur à SF-38-27, ou machine jamais connectée). L'écran d'appairage s'en sert pour conclure la
 * mise en service en nommant l'interpréteur ; il <b>omet la ligne</b> plutôt que d'écrire
 * « inconnu ».</p>
 *
 * <p>Champ <b>additif</b> : la forme de la réponse ne change pas pour un client antérieur, qui
 * l'ignore.</p>
 */
public record RunnerStatusResponse(boolean connected, OffsetDateTime lastSeenAt, String shell) {

    public static RunnerStatusResponse from(RunnerStatus status) {
        return new RunnerStatusResponse(status.connected(), status.lastSeenAt(), status.shell());
    }
}
