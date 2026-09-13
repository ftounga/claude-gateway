package fr.claudegateway.runner.host;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.seat.SeatLedgerService;
import fr.claudegateway.runner.host.dto.RunnerHostOverviewResponse;

/**
 * Les <b>espaces</b> d'un client (F-106 / SF-106-01) : dans lesquels il est visible, et les gestes
 * qui l'y activent ou l'en retirent.
 *
 * <p><b>Un poste, deux regards.</b> Activer un poste dans la Vigie n'appaire rien et ne copie rien ;
 * le retirer d'un espace ne supprime rien ailleurs — projets, terminaux, Radar et jetons restent.</p>
 *
 * <p><b>Poste sans ligne = Forge.</b> Un poste créé par un pod de la version précédente pendant un
 * déploiement progressif, ou tout jeu de données antérieur à la migration {@code 091}, n'a aucune
 * ligne : il est lu comme activé dans la Forge. Le premier geste d'activation ou de retrait
 * <b>matérialise</b> cette ligne avant d'agir, pour qu'aucun poste ne quitte la Forge par effet de
 * bord.</p>
 *
 * <p><b>Isolation</b> : chaque geste vérifie d'abord la possession du poste
 * ({@link RunnerHostService#requireOwned}) ; chaque lecture filtre {@code user_id}.</p>
 */
@Service
public class HostSpaceService {

    private final HostSpaceRepository repository;
    private final RunnerHostService hostService;
    private final SeatLedgerService seatLedger;

    public HostSpaceService(HostSpaceRepository repository, RunnerHostService hostService,
            SeatLedgerService seatLedger) {
        this.repository = repository;
        this.hostService = hostService;
        this.seatLedger = seatLedger;
    }

    /** Les espaces d'un poste possédé, dans l'ordre FORGE, VIGIE. */
    @Transactional(readOnly = true)
    public Set<ClientSpace> spacesOf(UUID userId, UUID hostId) {
        hostService.requireOwned(userId, hostId);
        return effective(repository.findByUserIdAndHostId(userId, hostId));
    }

    /** Les espaces de chaque poste de l'utilisateur ; un poste absent de la table n'y figure pas. */
    @Transactional(readOnly = true)
    public Map<UUID, Set<ClientSpace>> spacesByHost(UUID userId) {
        Map<UUID, Set<ClientSpace>> byHost = new HashMap<>();
        for (HostSpace row : repository.findByUserId(userId)) {
            byHost.computeIfAbsent(row.getHostId(), id -> EnumSet.noneOf(ClientSpace.class))
                    .add(row.getSpace());
        }
        return byHost;
    }

    /**
     * Les espaces d'un poste lus dans une carte déjà chargée ({@link #spacesByHost}) : aucun ⇒ Forge.
     */
    public static Set<ClientSpace> spacesIn(Map<UUID, Set<ClientSpace>> byHost, UUID hostId) {
        Set<ClientSpace> spaces = byHost.get(hostId);
        return spaces == null || spaces.isEmpty() ? EnumSet.of(ClientSpace.defaultSpace())
                : EnumSet.copyOf(spaces);
    }

    /** Vrai si ce poste possédé est activé dans l'espace. */
    @Transactional(readOnly = true)
    public boolean isActive(UUID userId, UUID hostId, ClientSpace space) {
        return spacesOf(userId, hostId).contains(space);
    }

    /**
     * Vrai si le poste de cet utilisateur est activé dans l'espace — <b>sans</b> vérifier la
     * possession : réservé aux traitements de fond dont le couple (utilisateur, poste) vient déjà
     * d'une ligne isolée (la planification du Radar). Un poste sans ligne est lu comme la Forge.
     */
    @Transactional(readOnly = true)
    public boolean isActiveForOwner(UUID userId, UUID hostId, ClientSpace space) {
        return effective(repository.findByUserIdAndHostId(userId, hostId)).contains(space);
    }

    /**
     * Exige que ce poste possédé soit activé dans l'espace.
     *
     * @throws RunnerHostNotFoundException si le poste est inconnu ou d'autrui (vérifié d'abord)
     * @throws HostNotInSpaceException     s'il est possédé mais pas activé dans cet espace
     */
    @Transactional(readOnly = true)
    public void requireActive(UUID userId, UUID hostId, ClientSpace space) {
        if (!spacesOf(userId, hostId).contains(space)) {
            throw new HostNotInSpaceException(space);
        }
    }

    /** Active un poste possédé dans un espace. Idempotent ; aucun appairage. */
    @Transactional
    public Set<ClientSpace> activate(UUID userId, UUID hostId, ClientSpace space) {
        Set<ClientSpace> current = materialize(userId, hostId);
        if (!current.contains(space)) {
            insert(userId, hostId, space);
            current.add(space);
        }
        return current;
    }

    /**
     * Retire un poste possédé d'un espace. Rien n'est supprimé ailleurs. Idempotent pour un espace où
     * le poste n'est pas.
     *
     * @throws HostLastSpaceException si c'est le dernier espace du poste
     */
    @Transactional
    public Set<ClientSpace> remove(UUID userId, UUID hostId, ClientSpace space) {
        Set<ClientSpace> current = materialize(userId, hostId);
        if (!current.contains(space)) {
            return current;
        }
        if (current.size() == 1) {
            throw new HostLastSpaceException();
        }
        // F-107 / SF-107-05 : le supplément est par espace — quitter un espace en cours de mois laisse le
        // mois engagé dans cet espace, comme une clôture de mission. Un poste clôturé n'engage plus rien.
        RunnerHost host = hostService.requireOwned(userId, hostId);
        if (host.getMissionStatus() != HostMissionStatus.CLOSED) {
            OffsetDateTime entered = space == ClientSpace.FORGE ? host.getCreatedAt()
                    : repository.findByUserIdAndHostId(userId, hostId).stream()
                            .filter(row -> row.getSpace() == space)
                            .map(HostSpace::getActivatedAt)
                            .findFirst().orElse(null);
            seatLedger.noteSpaceRemoval(userId, hostId, EntitlementSpace.valueOf(space.name()), entered);
        }
        repository.deleteOne(userId, hostId, space);
        current.remove(space);
        return current;
    }

    /**
     * Crée un poste <b>dans un espace</b>, en une transaction : le poste et sa première ligne
     * d'espace. « Connecter un client depuis la Vigie » ne le fait pas apparaître dans la Forge.
     */
    @Transactional
    public RunnerHost createHost(UUID userId, String name, ClientSpace space) {
        RunnerHost host = hostService.create(userId, name);
        insert(userId, host.getId(), space == null ? ClientSpace.defaultSpace() : space);
        return host;
    }

    /**
     * Garde de la vue d'ensemble les postes activés dans l'espace, chacun portant ses espaces.
     * « Hébergé » (poste virtuel, sans identifiant) ne vit que dans la Forge.
     */
    @Transactional(readOnly = true)
    public List<RunnerHostOverviewResponse> inSpace(UUID userId,
            List<RunnerHostOverviewResponse> overview, ClientSpace space) {
        Map<UUID, Set<ClientSpace>> byHost = spacesByHost(userId);
        return overview.stream()
                .map(host -> host.id() == null ? host.withSpaces(List.of(ClientSpace.FORGE.name()))
                        : host.withSpaces(spacesIn(byHost, host.id()).stream().map(Enum::name).toList()))
                .filter(host -> host.spaces().contains(space.name()))
                .toList();
    }

    /** Un poste supprimé ne laisse aucune ligne d'espace. */
    @EventListener
    @Transactional
    public void onHostLifecycle(RunnerHostLifecycleEvent event) {
        if (event.kind() == RunnerHostLifecycleEvent.Kind.DELETED) {
            repository.deleteByUserIdAndHostId(event.userId(), event.hostId());
        }
    }

    /** Purge à la suppression du compte. */
    @Transactional
    public void purgeUser(UUID userId) {
        repository.deleteByUserId(userId);
    }

    // ------------------------------------------------------------------ interne

    /** Vérifie la possession, puis écrit la ligne Forge implicite d'un poste qui n'en a aucune. */
    private Set<ClientSpace> materialize(UUID userId, UUID hostId) {
        hostService.requireOwned(userId, hostId);
        List<HostSpace> rows = repository.findByUserIdAndHostId(userId, hostId);
        if (rows.isEmpty()) {
            insert(userId, hostId, ClientSpace.defaultSpace());
            return EnumSet.of(ClientSpace.defaultSpace());
        }
        return effective(rows);
    }

    private void insert(UUID userId, UUID hostId, ClientSpace space) {
        repository.save(HostSpace.builder()
                .userId(userId)
                .hostId(hostId)
                .space(space)
                .activatedAt(OffsetDateTime.now())
                .build());
    }

    private static Set<ClientSpace> effective(List<HostSpace> rows) {
        if (rows.isEmpty()) {
            return EnumSet.of(ClientSpace.defaultSpace());
        }
        Set<ClientSpace> spaces = EnumSet.noneOf(ClientSpace.class);
        rows.forEach(row -> spaces.add(row.getSpace()));
        return spaces;
    }
}
