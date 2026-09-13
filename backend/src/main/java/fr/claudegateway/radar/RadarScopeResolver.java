package fr.claudegateway.radar;

import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.runner.host.ClientSpace;
import fr.claudegateway.runner.host.HostSpaceService;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * Fabrique le {@link RadarScope} d'une requête (F-99 / SF-99-01) : le poste doit appartenir à
 * l'utilisateur.
 *
 * <p>Un poste inconnu et un poste d'autrui sont <b>indiscernables</b> —
 * {@code RunnerHostNotFoundException}, 404 — comme partout ailleurs depuis F-48 : un 403 dirait qu'un
 * poste existe sous cet identifiant.</p>
 */
@Component
public class RadarScopeResolver {

    private final RunnerHostService hostService;
    private final HostSpaceService spaceService;

    public RadarScopeResolver(RunnerHostService hostService, HostSpaceService spaceService) {
        this.hostService = hostService;
        this.spaceService = spaceService;
    }

    /** Le périmètre, après vérification de la possession du poste. */
    public RadarScope require(UUID userId, UUID hostId) {
        hostService.requireOwned(userId, hostId);
        return new RadarScope(userId, hostId);
    }

    /**
     * Le périmètre d'une API <b>de la Vigie</b> (F-106 / SF-106-01) : possession d'abord (404), puis
     * activation du poste dans la Vigie (409 {@code host_not_in_space}). Export et purge n'y passent
     * pas : on récupère et on efface ses données même après avoir retiré le client.
     */
    public RadarScope requireInVigie(UUID userId, UUID hostId) {
        spaceService.requireActive(userId, hostId, ClientSpace.VIGIE);
        return new RadarScope(userId, hostId);
    }
}
