package fr.claudegateway.notifications;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushPreferences;

/**
 * <b>Les préférences de notification</b> (F-185 / SF-185-06) : lues par l'émetteur avant de faire
 * sonner un appareil, réglées depuis Paramètres > Notifications. Une ligne par compte, clé
 * {@code user_id} ; absente = tout sonne.
 */
@Service
public class NotificationPreferenceService implements PushPreferences {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private final NotificationPreferenceRepository repository;
    private final Clock clock;

    public NotificationPreferenceService(NotificationPreferenceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean allowsPush(UUID userId, PushEvent event) {
        if (userId == null) {
            return true;
        }
        return NotificationPreferences.fromRow(repository.findById(userId).orElse(null))
                .allowsPush(event, clock.instant());
    }

    @Transactional(readOnly = true)
    public NotificationPreferencesView get(UUID userId) {
        return view(NotificationPreferences.fromRow(repository.findById(userId).orElse(null)));
    }

    /** @throws IllegalArgumentException demande invalide (400) — message lisible */
    @Transactional
    public NotificationPreferencesView save(UUID userId, NotificationPreferencesRequest request) {
        NotificationPreferences parsed = NotificationPreferences.parse(request.mutedEvents(),
                request.quietFrom(), request.quietTo(), request.timeZone());
        repository.save(NotificationPreference.builder()
                .userId(userId)
                .mutedEvents(parsed.muted().stream().map(Enum::name).sorted()
                        .reduce((a, b) -> a + "," + b).orElse(null))
                .quietFrom(parsed.quietFrom() == null ? null : parsed.quietFrom().format(HOUR))
                .quietTo(parsed.quietTo() == null ? null : parsed.quietTo().format(HOUR))
                .timeZone(parsed.zone().getId())
                .updatedAt(OffsetDateTime.now(clock))
                .build());
        return view(parsed);
    }

    private static NotificationPreferencesView view(NotificationPreferences preferences) {
        return new NotificationPreferencesView(
                preferences.muted().stream().map(Enum::name).sorted().toList(),
                preferences.quietFrom() == null ? null : preferences.quietFrom().format(HOUR),
                preferences.quietTo() == null ? null : preferences.quietTo().format(HOUR),
                preferences.zone().getId(),
                Arrays.stream(PushEvent.values())
                        .map(e -> new NotificationPreferencesView.EventView(e.name(), e.title(), e.critical()))
                        .toList());
    }
}
