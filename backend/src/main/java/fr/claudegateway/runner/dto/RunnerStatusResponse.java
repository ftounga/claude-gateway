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
 * <p>{@code paired} (F-82 / SF-82-04) dit que le poste porte <b>un jeton encore utilisable</b> —
 * ni révoqué, ni expiré. Il se lit avec {@code connected}, jamais à sa place : « non connecté mais
 * appairé » est le cas d'une machine qu'on rallume, et elle n'a besoin d'aucun code d'appairage,
 * seulement qu'on relance le runner. Après un coupe-circuit (SF-38-08) il repasse à {@code false},
 * et l'écran redemande alors un code — ce qui est le seul geste qui puisse aboutir.</p>
 *
 * <p>{@code hostId}, {@code hostName}, {@code rootName} et {@code elevated} (F-48) décrivent le
 * <b>poste</b> : ils sont {@code null} / {@code false} pour un projet rattaché à aucune machine —
 * l'état d'un projet qu'on vient de créer, que l'écran doit pouvoir dire. {@code rootName} n'est que
 * le <b>dernier segment</b> de la racine déclarée, jamais le chemin absolu.</p>
 */
public record RunnerStatusResponse(boolean connected, boolean paired, OffsetDateTime lastSeenAt,
        String shell, UUID hostId, String hostName, String rootName, boolean elevated) {

    public static RunnerStatusResponse from(RunnerStatus status) {
        return new RunnerStatusResponse(status.connected(), status.paired(), status.lastSeenAt(),
                status.shell(), status.hostId(), status.hostName(), status.rootName(),
                status.elevated());
    }
}
