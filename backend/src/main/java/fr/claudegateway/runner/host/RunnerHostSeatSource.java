package fr.claudegateway.runner.host;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.seat.SeatSource;

/**
 * Branche les <b>postes</b> sur la facturation au client (F-65 / SF-65-01, par espace depuis F-107 /
 * SF-107-05) : c'est ici, et pas dans le module de facturation, qu'on sait qu'« être facturable » se lit sur
 * l'état de mission de F-60 et qu'« être dans un espace » se lit sur {@code host_spaces} (F-106).
 *
 * <p>Un poste est facturable tant que sa mission n'est pas <b>clôturée</b>. Il l'est dans un espace s'il y est
 * activé ; un poste <b>sans aucune ligne d'espace</b> relève de la Forge (règle de F-106). Dans la Forge, sa
 * création date son entrée ; dans la Vigie, son activation.</p>
 *
 * <p>Isolation : toutes les lectures partent du {@code userId} du contexte de sécurité. Ce composant lit le
 * dépôt des espaces, jamais {@code HostSpaceService} (qui dépend lui-même du registre des mois-clients).</p>
 */
@Component
public class RunnerHostSeatSource implements SeatSource {

    private final RunnerHostRepository repository;
    private final HostSpaceRepository spaces;

    public RunnerHostSeatSource(RunnerHostRepository repository, HostSpaceRepository spaces) {
        this.repository = repository;
        this.spaces = spaces;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillableSeat> billableSeats(UUID userId) {
        return billableSeats(userId, EntitlementSpace.FORGE);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillableSeat> billableSeats(UUID userId, EntitlementSpace space) {
        Map<UUID, List<HostSpace>> rowsByHost = spaces.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(HostSpace::getHostId));
        return repository
                .findByUserIdAndMissionStatusNotOrderByCreatedAtAsc(userId, HostMissionStatus.CLOSED)
                .stream()
                .filter(host -> isIn(rowsByHost.get(host.getId()), space))
                .map(host -> new BillableSeat(host.getId(), host.getName(),
                        space == EntitlementSpace.FORGE ? host.getCreatedAt()
                                : activation(rowsByHost.get(host.getId()), space)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<EntitlementSpace> spacesOf(UUID userId, UUID hostId) {
        List<HostSpace> rows = spaces.findByUserIdAndHostId(userId, hostId);
        if (rows.isEmpty()) {
            return EnumSet.of(EntitlementSpace.FORGE);
        }
        Set<EntitlementSpace> result = EnumSet.noneOf(EntitlementSpace.class);
        rows.forEach(row -> result.add(EntitlementSpace.valueOf(row.getSpace().name())));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public OffsetDateTime enteredSpaceAt(UUID userId, UUID hostId, EntitlementSpace space) {
        if (space == EntitlementSpace.FORGE) {
            return null;
        }
        return activation(spaces.findByUserIdAndHostId(userId, hostId), space);
    }

    @Override
    @Transactional(readOnly = true)
    public String seatName(UUID userId, UUID hostId) {
        return repository.findByIdAndUserId(hostId, userId)
                .map(RunnerHost::getName)
                .orElse(null);
    }

    private static boolean isIn(List<HostSpace> rows, EntitlementSpace space) {
        if (rows == null || rows.isEmpty()) {
            return space == EntitlementSpace.FORGE;
        }
        return rows.stream().anyMatch(row -> row.getSpace().name().equals(space.name()));
    }

    private static OffsetDateTime activation(List<HostSpace> rows, EntitlementSpace space) {
        if (rows == null) {
            return null;
        }
        return rows.stream()
                .filter(row -> row.getSpace().name().equals(space.name()))
                .map(HostSpace::getActivatedAt)
                .findFirst()
                .orElse(null);
    }
}
