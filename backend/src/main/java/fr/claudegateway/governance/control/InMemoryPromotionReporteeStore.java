package fr.claudegateway.governance.control;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import fr.claudegateway.governance.control.PromotionReportee.Report;

/**
 * Le registre des promotions reportées <b>en mémoire du processus</b> (F-93 / SF-93-04).
 *
 * <p>C'était le seul support en SF-93-04 : rapide, sans migration, mais qu'un redémarrage ou un autre
 * pod oublie. SF-93-05 l'a réduit à ce qu'il est vraiment — un store parmi deux — et l'a gardé pour
 * les tests et comme repli. La sémantique (fusion, durée de vie, bornes, isolation) est partagée avec
 * {@link JpaPromotionReporteeStore} via les aides de {@link PromotionReportee}.</p>
 */
public class InMemoryPromotionReporteeStore implements PromotionReporteeStore {

    private record Key(UUID userId, UUID hostId, UUID workspaceId) {
    }

    private final Map<Key, Report> entries = new LinkedHashMap<>();
    private final Clock clock;

    public InMemoryPromotionReporteeStore() {
        this(Clock.systemUTC());
    }

    InMemoryPromotionReporteeStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void reporter(UUID userId, UUID hostId, UUID workspaceId,
            Collection<String> elements, int dette) {
        Instant now = clock.instant();
        purge(now);
        Key key = new Key(userId, hostId, workspaceId);
        Report previous = entries.remove(key);
        List<String> merged =
                PromotionReportee.mergeElements(previous == null ? null : previous.elements(), elements);
        int kept = PromotionReportee.mergedDette(previous == null ? 0 : previous.dette(), dette);
        entries.put(key, new Report(merged, kept, previous == null ? now : previous.reportedAt()));
        while (entries.size() > PromotionReportee.MAX_ENTRIES) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    @Override
    public synchronized Optional<Report> reclamer(UUID userId, UUID hostId, UUID workspaceId) {
        purge(clock.instant());
        return Optional.ofNullable(entries.remove(new Key(userId, hostId, workspaceId)));
    }

    @Override
    public synchronized boolean estDue(UUID userId, UUID hostId, UUID workspaceId) {
        purge(clock.instant());
        return entries.containsKey(new Key(userId, hostId, workspaceId));
    }

    private void purge(Instant now) {
        entries.values().removeIf(report -> report.reportedAt().plus(PromotionReportee.TTL).isBefore(now));
    }
}
