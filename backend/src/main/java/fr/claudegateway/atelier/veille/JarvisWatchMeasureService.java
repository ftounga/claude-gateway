package fr.claudegateway.atelier.veille;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>La mesure d'adoption des veilles</b> (F-230 / SF-230-04, D8) — la leçon « mécanisme livré qui dort » (routines
 * F-183 : 0 configurée) : veilles posées, passages, changements, alertes, alertes ouvertes, coût IA en euros.
 * Revue à J+7 et J+14. Sous {@code user_id} : l'administrateur mesure SES veilles (même patron que F-176).
 */
@Service
public class JarvisWatchMeasureService {

    /** La mesure sur une fenêtre. */
    public record Measure(int days, int watches, int active, int proposed, int broken, int passages, int changes,
            int failures, int missed, int alerts, int alertsOpened, BigDecimal aiCostEur) {
    }

    private final JarvisWatchRepository watches;
    private final JarvisWatchRunRepository runs;
    private final JarvisWatchAlertRepository alerts;
    private final JarvisAlertService alertService;
    private final Clock clock;

    public JarvisWatchMeasureService(JarvisWatchRepository watches, JarvisWatchRunRepository runs,
            JarvisWatchAlertRepository alerts, JarvisAlertService alertService, Clock clock) {
        this.watches = watches;
        this.runs = runs;
        this.alerts = alerts;
        this.alertService = alertService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Measure measure(UUID userId, int days) {
        OffsetDateTime since = OffsetDateTime.now(clock).minusDays(days);
        List<JarvisWatch> all = watches.findByUserIdOrderByCreatedAtAsc(userId);
        List<JarvisWatchRun> window = runs.findByUserIdAndStartedAtGreaterThanEqual(userId, since);
        List<JarvisWatchAlert> raised = alerts.findByUserIdAndCreatedAtGreaterThanEqual(userId, since);
        int passages = (int) window.stream().filter(r -> !JarvisWatchRun.MISSED_OFFLINE.equals(r.getStatus())).count();
        int changes = (int) window.stream().filter(r -> JarvisWatchRun.CHANGED.equals(r.getStatus())).count();
        int failures = (int) window.stream().filter(r -> JarvisWatchRun.FAILED.equals(r.getStatus())).count();
        int missed = (int) window.stream().filter(r -> JarvisWatchRun.MISSED_OFFLINE.equals(r.getStatus())).count();
        BigDecimal costUsd = raised.stream().map(JarvisWatchAlert::getCostUsd)
                .reduce(BigDecimal.ZERO, (a, b) -> b == null ? a : a.add(b));
        return new Measure(days, all.size(), (int) all.stream().filter(JarvisWatch::isScheduled).count(),
                (int) all.stream().filter(JarvisWatch::isProposed).count(),
                (int) all.stream().filter(JarvisWatch::isBroken).count(), passages, changes, failures, missed,
                raised.size(), (int) raised.stream().filter(a -> a.getOpenedAt() != null).count(),
                alertService.eur(costUsd).setScale(2, RoundingMode.HALF_UP));
    }
}
