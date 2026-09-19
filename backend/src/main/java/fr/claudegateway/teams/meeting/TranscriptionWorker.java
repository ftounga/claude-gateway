package fr.claudegateway.teams.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Le <b>worker de transcription</b> (F-128 / SF-128-04) : réclame périodiquement les réunions
 * {@code PENDING} et les fait transcrire <b>hors du thread HTTP</b> (règle CLAUDE.md : traitements
 * lourds asynchrones). Aligné sur {@code OcrPollingWorker}/{@code IngestionWorker}.
 *
 * <p><b>Best-effort strict</b> : une erreur inattendue d'un cycle est journalisée sans détail et ne tue
 * jamais le planificateur. Désactivable par configuration (déterminisme des tests).</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.stt.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TranscriptionWorker {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionWorker.class);

    private final TranscriptionService transcriptionService;

    public TranscriptionWorker(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    @Scheduled(fixedDelayString = "${app.stt.worker.interval:20000}")
    public void poll() {
        try {
            int processed = transcriptionService.transcribePending();
            if (processed > 0) {
                log.debug("Transcription : {} réunion(s) traitée(s) ce cycle", processed);
            }
        } catch (RuntimeException ex) {
            log.warn("Cycle de transcription interrompu par une erreur inattendue");
        }
    }
}
