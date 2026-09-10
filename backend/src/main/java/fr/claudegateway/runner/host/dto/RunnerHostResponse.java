package fr.claudegateway.runner.host.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.atelier.RunnerShell;
import fr.claudegateway.runner.host.HostMissionStatus;
import fr.claudegateway.runner.host.RunnerHost;

/**
 * Vue d'un poste (F-48 / SF-48-01) : ce que l'utilisateur en voit à l'écran.
 *
 * <p>{@code rootName} est le <b>dernier segment</b> de la racine déclarée par le runner, jamais le
 * chemin absolu de la machine. {@code shell} repasse par la liste blanche de {@link RunnerShell}
 * avant de sortir de la gateway : une valeur inconnue devient {@code null} plutôt que d'être relayée
 * telle quelle à l'écran.</p>
 *
 * <p>{@code missionStatus} (F-60 / SF-60-01) est l'état <b>métier</b> déclaré par le propriétaire —
 * où en est la mission chez ce client. Il est <b>indépendant</b> de {@code connected}, qui est
 * l'état <b>technique</b> : un poste éteint peut porter une mission active en pause, et un poste
 * connecté une mission close que personne n'a rangée.</p>
 *
 * @param connected     vrai si un runner de ce poste est joignable maintenant (tous replicas confondus)
 * @param missionStatus état de mission déclaré ; jamais nul
 */
public record RunnerHostResponse(
        UUID id,
        String name,
        String rootName,
        String os,
        String shell,
        Boolean elevated,
        boolean connected,
        HostMissionStatus missionStatus,
        OffsetDateTime lastSeenAt,
        OffsetDateTime createdAt) {

    public static RunnerHostResponse from(RunnerHost host, boolean connected) {
        return new RunnerHostResponse(
                host.getId(),
                host.getName(),
                host.getRootName(),
                host.getOs(),
                RunnerShell.fromDeclared(host.getShell()).map(RunnerShell::declared).orElse(null),
                host.getElevated(),
                connected,
                host.getMissionStatus() == null ? HostMissionStatus.defaultStatus()
                        : host.getMissionStatus(),
                host.getLastSeenAt(),
                host.getCreatedAt());
    }
}
