package fr.claudegateway.teams.meeting;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Le <b>worker de rétention des réunions</b> (F-128 / SF-128-07) : purge périodiquement les médias lourds
 * (audio + images) des réunions dont la conservation est dépassée (règle CLAUDE.md : traitements lourds
 * asynchrones). Aligné sur {@code RunnerDiagPurgeWorker}/{@code RadarDormancyWorker}.
 *
 * <p><b>Best-effort strict</b> : une erreur inattendue d'un cycle est journalisée sans détail et ne tue
 * jamais le planificateur. Désactivable par configuration (déterminisme des tests).</p>
 */
@Component
@ConditionalOnProperty(name = "app.teams.meeting.purge.enabled", havingValue = "true", matchIfMissing = true)
public class MeetingRetentionWorker {

    private static final Logger log = LoggerFactory.getLogger(MeetingRetentionWorker.class);

    private final MeetingRetentionService retentionService;

    public MeetingRetentionWorker(MeetingRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Scheduled(cron = "${app.teams.meeting.purge.cron:0 40 3 * * *}")
    public void purge() {
        try {
            retentionService.purgeExpired(OffsetDateTime.now());
        } catch (RuntimeException ex) {
            log.warn("Cycle de rétention des réunions interrompu par une erreur inattendue");
        }
    }
}
