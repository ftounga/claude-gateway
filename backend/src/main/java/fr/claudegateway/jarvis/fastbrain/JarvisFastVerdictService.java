package fr.claudegateway.jarvis.fastbrain;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * F-252 / SF-252-02 (D3) — <b>le verdict d'une réponse provisoire</b>, entre le pod qui reçoit l'écran et celui qui tient
 * le flux. Le premier verdict fait foi ; sans verdict sous {@link #WAIT}, la réponse est jetée.
 */
@Service
public class JarvisFastVerdictService {

    private static final Logger log = LoggerFactory.getLogger(JarvisFastVerdictService.class);

    /** Au-delà, la réponse provisoire est jetée (D3). */
    static final Duration WAIT = Duration.ofSeconds(8);
    private static final long POLL_MS = 60L;
    /** Une ligne jamais consommée (flux interrompu) ne reste pas : purgée au-delà. */
    static final Duration RETENTION = Duration.ofHours(1);

    /** Le verdict : adoptée (avec le texte de référence) ou jetée. */
    public record Verdict(boolean adopted, String text) {
    }

    private final JarvisFastVerdictRepository repository;
    private final Clock clock;

    public JarvisFastVerdictService(JarvisFastVerdictRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Dépose le verdict de {@code userId}. Un verdict déjà déposé n'est pas remplacé. */
    public void record(UUID userId, UUID speculationId, boolean adopted, String text) {
        if (repository.findByUserIdAndSpeculationId(userId, speculationId).isPresent()) {
            return;
        }
        try {
            repository.saveAndFlush(JarvisFastVerdict.builder().id(UUID.randomUUID()).userId(userId)
                    .speculationId(speculationId).adopted(adopted).text(text).createdAt(OffsetDateTime.now(clock))
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.debug("Verdict déjà déposé");
        }
    }

    /** Attend le verdict de {@code userId} au plus {@link #WAIT}, le consomme et le rend ; vide au-delà. */
    public Optional<Verdict> await(UUID userId, UUID speculationId) {
        return await(userId, speculationId, WAIT);
    }

    Optional<Verdict> await(UUID userId, UUID speculationId, Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        for (;;) {
            Optional<JarvisFastVerdict> found = repository.findByUserIdAndSpeculationId(userId, speculationId);
            if (found.isPresent()) {
                repository.deleteOwn(found.get().getId(), userId);
                return Optional.of(new Verdict(found.get().isAdopted(), found.get().getText()));
            }
            if (System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /** Purge des verdicts orphelins (tâche technique, comme les autres purges Jarvis). */
    @Scheduled(cron = "${app.jarvis.fast-brain.verdict-purge-cron:0 */10 * * * *}", zone = "Europe/Paris")
    public void purge() {
        try {
            repository.deleteOlderThan(OffsetDateTime.now(clock).minus(RETENTION));
        } catch (RuntimeException e) {
            log.warn("Purge des verdicts en échec : {}", e.getClass().getSimpleName());
        }
    }
}
