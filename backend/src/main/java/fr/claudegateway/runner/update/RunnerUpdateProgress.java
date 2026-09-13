package fr.claudegateway.runner.update;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * La dernière mise à jour d'un poste, telle que l'écran la montre (F-111 / SF-111-04).
 *
 * @param id          identifiant de la ligne de journal (= {@code updateId} de la trame)
 * @param state       {@code REQUESTED}, {@code DOWNLOADING}, {@code WAITING}, {@code RESTARTING},
 *                    {@code SUCCEEDED}, {@code FAILED}, {@code ROLLED_BACK}
 * @param fromVersion version du runner au moment de la demande
 * @param toVersion   version visée
 * @param detail      motif d'échec, ou activités attendues ({@code commande, capture})
 * @param forced      vrai si « Forcer » a été demandé
 * @param active      vrai si la mise à jour est en cours — état non terminal, nouvelles depuis moins
 *                    de {@link #STALE_AFTER}
 */
public record RunnerUpdateProgress(UUID id, String state, String fromVersion, String toVersion, String detail,
        boolean forced, OffsetDateTime requestedAt, OffsetDateTime updatedAt, OffsetDateTime finishedAt,
        boolean active) {

    /** Au-delà, une mise à jour sans nouvelles n'est plus « en cours » : l'écran redit la présence réelle. */
    public static final Duration STALE_AFTER = Duration.ofMinutes(10);

    public static RunnerUpdateProgress of(RunnerUpdateJournalEntry entry, OffsetDateTime now) {
        boolean active = !entry.getState().terminal()
                && entry.getUpdatedAt().isAfter(now.minus(STALE_AFTER));
        return new RunnerUpdateProgress(entry.getId(), entry.getState().name(), entry.getFromVersion(),
                entry.getToVersion(), entry.getDetail(), entry.isForced(), entry.getRequestedAt(),
                entry.getUpdatedAt(), entry.getFinishedAt(), active);
    }
}
