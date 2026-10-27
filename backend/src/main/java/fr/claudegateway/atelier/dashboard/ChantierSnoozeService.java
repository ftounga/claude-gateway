package fr.claudegateway.atelier.dashboard;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.journey.ChantierPreflightService;
import fr.claudegateway.atelier.journey.InvalidJourneyException;

/**
 * <b>Reporter un chantier</b> (F-220 / SF-220-05) : il quitte les groupes du tableau du client jusqu'à une date,
 * et le rappel des dormants l'ignore. Un report remplace le précédent ; « ne plus reporter » est idempotent.
 *
 * <p><b>Isolation</b> : {@code requireOwned(userId, workspaceId)} d'abord (via le préflight F-219 : 404 sur le
 * terminal d'autrui, 400 sur un chantier qui n'est pas ouvert) ; chaque ligne porte {@code user_id}.</p>
 */
@Service
public class ChantierSnoozeService {

    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 30;

    private final ChantierPreflightService preflights;
    private final ChantierSnoozeRepository repository;
    private final Clock clock;

    public ChantierSnoozeService(ChantierPreflightService preflights, ChantierSnoozeRepository repository,
            Clock clock) {
        this.preflights = preflights;
        this.repository = repository;
        this.clock = clock;
    }

    /** Le report d'un chantier, tel que l'écran le lit. */
    public record SnoozeView(int number, OffsetDateTime until) {
    }

    /**
     * Reporte le chantier ouvert {@code number} de {@code days} jours.
     *
     * @throws InvalidJourneyException {@code days} hors de 1 à 30, ou chantier non ouvert (400)
     */
    @Transactional
    public SnoozeView snooze(UUID userId, UUID workspaceId, int number, Integer days) {
        preflights.one(userId, workspaceId, number); // 404 sur le terminal d'autrui, 400 si non ouvert
        if (days == null || days < MIN_DAYS || days > MAX_DAYS) {
            throw new InvalidJourneyException("Reporter de " + MIN_DAYS + " à " + MAX_DAYS + " jours.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime until = now.plusDays(days);
        ChantierSnooze row = repository.findByUserIdAndWorkspaceIdAndChantierNumber(userId, workspaceId, number)
                .orElseGet(() -> ChantierSnooze.builder().userId(userId).workspaceId(workspaceId)
                        .chantierNumber(number).createdAt(now).build());
        row.setSnoozedUntil(until);
        row.setUpdatedAt(now);
        repository.save(row);
        return new SnoozeView(number, until);
    }

    /** Ne plus reporter le chantier {@code number} (idempotent). 404 sur le terminal d'autrui. */
    @Transactional
    public void unsnooze(UUID userId, UUID workspaceId, int number) {
        preflights.all(userId, workspaceId); // requireOwned d'abord — 404 sur le terminal d'autrui
        repository.deleteChantier(userId, workspaceId, number);
    }
}
