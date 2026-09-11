package fr.claudegateway.runner.host;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.billing.seat.SeatSource;

/**
 * Branche les <b>postes</b> sur la facturation au poste (F-65 / SF-65-01) : c'est ici, et pas dans
 * le module de facturation, qu'on sait qu'« être facturable » se lit sur l'état de mission de F-60.
 *
 * <p>Un poste est facturable tant que sa mission n'est pas <b>clôturée</b> — une mission
 * {@code PENDING} en fait partie : elle est gardée ouverte, poste appairé et projets rattachés.
 * Seule la clôture sort du décompte, et c'est le geste par lequel le consultant cesse de payer.</p>
 *
 * <p>Isolation : la seule lecture part du {@code userId} du contexte de sécurité.</p>
 */
@Component
public class RunnerHostSeatSource implements SeatSource {

    private final RunnerHostRepository repository;

    public RunnerHostSeatSource(RunnerHostRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillableSeat> billableSeats(UUID userId) {
        return repository
                .findByUserIdAndMissionStatusNotOrderByCreatedAtAsc(userId, HostMissionStatus.CLOSED)
                .stream()
                .map(host -> new BillableSeat(host.getId(), host.getName(), host.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String seatName(UUID userId, UUID hostId) {
        return repository.findByIdAndUserId(hostId, userId)
                .map(RunnerHost::getName)
                .orElse(null);
    }
}
