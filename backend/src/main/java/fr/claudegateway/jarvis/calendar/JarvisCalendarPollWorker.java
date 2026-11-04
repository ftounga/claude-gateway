package fr.claudegateway.jarvis.calendar;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>Le planificateur de la relève des agendas</b> (F-243 / SF-243-01, D2, D13) — toutes les 60 s il relève les agendas
 * <b>dus</b> (chacun toutes les 15 min) ; chaque nuit, les occurrences finies depuis plus de 7 jours sont purgées.
 * Coupe-circuit {@code APP_JARVIS_CALENDAR_ENABLED} ; éteint en tests, où {@link JarvisCalendarPoller#runOnce()} est
 * appelé directement.
 */
@Component
@ConditionalOnProperty(name = "app.jarvis.calendar.enabled", havingValue = "true", matchIfMissing = true)
public class JarvisCalendarPollWorker {

    private static final Logger log = LoggerFactory.getLogger(JarvisCalendarPollWorker.class);

    private final JarvisCalendarPoller poller;
    private final JarvisCalendarEventRepository events;
    private final JarvisCalendarProperties properties;
    private final Clock clock;

    public JarvisCalendarPollWorker(JarvisCalendarPoller poller, JarvisCalendarEventRepository events,
            JarvisCalendarProperties properties, Clock clock) {
        this.poller = poller;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jarvis.calendar.tick:60000}",
            initialDelayString = "${app.jarvis.calendar.initial-delay:75000}")
    public void tick() {
        try {
            poller.runOnce();
        } catch (RuntimeException e) {
            log.warn("Agenda : passage de relève interrompu ({})", e.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "${app.jarvis.calendar.purge-cron:0 55 3 * * *}")
    public void purge() {
        int purged = purgeOnce();
        if (purged > 0) {
            log.info("Agenda : {} occurrence(s) de plus de {} jours purgée(s)", purged, properties.retentionDays());
        }
    }

    /** D13 — les occurrences finies depuis plus de {@code retentionDays} jours. */
    public int purgeOnce() {
        return events.purgeBefore(OffsetDateTime.now(clock).minusDays(properties.retentionDays()));
    }
}
