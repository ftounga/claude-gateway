package fr.claudegateway.runner.host.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.atelier.RunnerShell;
import fr.claudegateway.runner.host.RunnerHost;

/**
 * Vue d'un poste (F-48 / SF-48-01) : ce que l'utilisateur en voit à l'écran.
 *
 * <p>{@code rootName} est le <b>dernier segment</b> de la racine déclarée par le runner, jamais le
 * chemin absolu de la machine. {@code shell} repasse par la liste blanche de {@link RunnerShell}
 * avant de sortir de la gateway : une valeur inconnue devient {@code null} plutôt que d'être relayée
 * telle quelle à l'écran.</p>
 *
 * @param connected vrai si un runner de ce poste est joignable maintenant (tous replicas confondus)
 */
public record RunnerHostResponse(
        UUID id,
        String name,
        String rootName,
        String os,
        String shell,
        Boolean elevated,
        boolean connected,
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
                host.getLastSeenAt(),
                host.getCreatedAt());
    }
}
