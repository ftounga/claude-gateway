package fr.claudegateway.governance.control;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.governance.control.PromotionReportee.Report;

/**
 * Le registre des promotions reportées <b>persisté en base</b> (F-93 / SF-93-05) : le bean de
 * production. Un report survit à un redémarrage et à un changement de pod, et il n'est réclamé
 * qu'une fois même si deux pods jugent le même tour au même instant.
 *
 * <p>La sémantique (fusion, durée de vie, bornes, isolation) est celle de {@link PromotionReportee} ;
 * cette classe ne fait que la porter en base, transaction par transaction.</p>
 */
@Component
@Primary
public class JpaPromotionReporteeStore implements PromotionReporteeStore {

    private final PromotionReporteeRepository repository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public JpaPromotionReporteeStore(PromotionReporteeRepository repository) {
        this(repository, Clock.systemUTC());
    }

    JpaPromotionReporteeStore(PromotionReporteeRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void reporter(UUID userId, UUID hostId, UUID workspaceId, Collection<String> elements,
            int dette) {
        purgeExpired();
        try {
            merge(userId, hostId, workspaceId, elements, dette);
        } catch (DataIntegrityViolationException concurrentInsert) {
            // Un autre pod a inséré le même triple entre notre lecture et notre écriture : la ligne
            // existe maintenant, on refusionne dessus plutôt que d'échouer le tour.
            merge(userId, hostId, workspaceId, elements, dette);
        }
        prune();
    }

    private void merge(UUID userId, UUID hostId, UUID workspaceId, Collection<String> elements,
            int dette) {
        Optional<PromotionReporteeEntity> existing =
                repository.findByUserIdAndHostIdAndWorkspaceId(userId, hostId, workspaceId);
        List<String> previous =
                existing.map(entity -> PromotionReportee.splitElements(entity.getElements())).orElse(null);
        List<String> merged = PromotionReportee.mergeElements(previous, elements);
        int kept = PromotionReportee.mergedDette(existing.map(PromotionReporteeEntity::getDette).orElse(0),
                dette);
        PromotionReporteeEntity entity = existing.orElseGet(() -> PromotionReporteeEntity.builder()
                .userId(userId).hostId(hostId).workspaceId(workspaceId)
                .reportedAt(OffsetDateTime.now(clock))
                .build());
        entity.setElements(PromotionReportee.joinElements(merged));
        entity.setDette(kept);
        repository.save(entity);
    }

    @Override
    @Transactional
    public Optional<Report> reclamer(UUID userId, UUID hostId, UUID workspaceId) {
        purgeExpired();
        Optional<PromotionReporteeEntity> locked =
                repository.lockByTriple(userId, hostId, workspaceId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        PromotionReporteeEntity entity = locked.get();
        Report report = new Report(PromotionReportee.splitElements(entity.getElements()),
                entity.getDette(), entity.getReportedAt().toInstant());
        repository.delete(entity);
        return Optional.of(report);
    }

    @Override
    @Transactional
    public boolean estDue(UUID userId, UUID hostId, UUID workspaceId) {
        purgeExpired();
        return repository.existsByUserIdAndHostIdAndWorkspaceId(userId, hostId, workspaceId);
    }

    /** Oublie les reports plus vieux que la durée de vie : le rappel nominatif n'a plus de sens. */
    private void purgeExpired() {
        repository.deleteByReportedAtBefore(OffsetDateTime.now(clock).minus(PromotionReportee.TTL));
    }

    /** Ramène le registre sous sa borne d'entrées : les moins récentes sortent. */
    private void prune() {
        long total = repository.count();
        if (total <= PromotionReportee.MAX_ENTRIES) {
            return;
        }
        int excess = (int) Math.min(total - PromotionReportee.MAX_ENTRIES, Integer.MAX_VALUE);
        List<UUID> oldest = repository.findOldestIds(Pageable.ofSize(excess));
        repository.deleteAllById(oldest);
    }
}
