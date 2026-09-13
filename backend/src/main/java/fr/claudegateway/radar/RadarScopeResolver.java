package fr.claudegateway.radar;

import java.util.UUID;

import org.springframework.stereotype.Component;

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

    public RadarScopeResolver(RunnerHostService hostService) {
        this.hostService = hostService;
    }

    /** Le périmètre, après vérification de la possession du poste. */
    public RadarScope require(UUID userId, UUID hostId) {
        hostService.requireOwned(userId, hostId);
        return new RadarScope(userId, hostId);
    }
}
