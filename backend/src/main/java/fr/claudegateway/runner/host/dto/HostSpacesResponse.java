package fr.claudegateway.runner.host.dto;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.runner.host.ClientSpace;
import fr.claudegateway.runner.host.HostMissionStatus;
import fr.claudegateway.runner.host.RunnerHost;

/**
 * Un poste et les espaces où il est activé (F-106 / SF-106-01). C'est la liste d'où l'écran propose
 * « activer dans l'autre espace » : le nom et l'état de mission suffisent à reconnaître un client.
 *
 * @param spaces {@code FORGE}, {@code VIGIE}, dans cet ordre
 */
public record HostSpacesResponse(UUID hostId, String name, HostMissionStatus missionStatus,
        List<String> spaces) {

    public static HostSpacesResponse from(RunnerHost host, Collection<ClientSpace> spaces) {
        return new HostSpacesResponse(host.getId(), host.getName(),
                host.getMissionStatus() == null ? HostMissionStatus.defaultStatus()
                        : host.getMissionStatus(),
                spaces.stream().sorted().map(Enum::name).toList());
    }
}
