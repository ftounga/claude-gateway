package fr.claudegateway.notifications;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.push.NotificationJournal;
import fr.claudegateway.push.PushEvent;

/**
 * <b>Le centre de notifications</b> (F-185 / SF-185-04) : la trace de chaque chose qui a attendu
 * l'utilisateur, lue par la cloche. Inscrit par l'émetteur (via {@link NotificationJournal}) même
 * sans appareil abonné : le centre ne dépend pas du push.
 *
 * <p>Scellé par {@code user_id} de bout en bout : le sujet est lu par {@code findByIdAndUserId},
 * chaque lecture et chaque écriture portent le compte.</p>
 */
@Service
public class NotificationCenterService implements NotificationJournal {

    /** Ce que la cloche montre. */
    static final int PAGE_SIZE = 30;

    /** Au-delà, une notification n'apprend plus rien à personne. */
    static final Duration RETENTION = Duration.ofDays(30);

    private final UserNotificationRepository repository;
    private final WorkspaceRepository workspaces;
    private final Clock clock;

    public NotificationCenterService(UserNotificationRepository repository, WorkspaceRepository workspaces,
            Clock clock) {
        this.repository = repository;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void record(UUID userId, UUID workspaceId, PushEvent event, boolean alreadySeen) {
        if (userId == null || event == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        repository.deleteOlderThan(userId, now.minus(RETENTION));
        repository.save(UserNotification.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .event(event.name())
                .subject(subject(userId, workspaceId))
                .createdAt(now)
                .readAt(alreadySeen ? now : null)
                .build());
    }

    /** Les non-lus et les {@value #PAGE_SIZE} dernières notifications de ce compte. */
    @Transactional(readOnly = true)
    public NotificationView list(UUID userId) {
        var items = repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, PAGE_SIZE)).stream()
                .map(n -> new NotificationView.Item(n.getId(), n.getEvent(), title(n.getEvent()), n.getSubject(),
                        n.getWorkspaceId(), n.getCreatedAt(), n.getReadAt() != null))
                .toList();
        return new NotificationView(repository.countByUserIdAndReadAtIsNull(userId), items);
    }

    /** Marque lue. {@code false} : la notification n'existe pas pour ce compte (404, indiscernable). */
    @Transactional
    public boolean markRead(UUID userId, UUID id) {
        if (!repository.existsByIdAndUserId(id, userId)) {
            return false;
        }
        repository.markRead(userId, id, OffsetDateTime.now(clock));
        return true;
    }

    @Transactional
    public void markAllRead(UUID userId) {
        repository.markAllRead(userId, OffsetDateTime.now(clock));
    }

    private String subject(UUID userId, UUID workspaceId) {
        if (workspaceId == null) {
            return null;
        }
        return workspaces.findByIdAndUserId(workspaceId, userId)
                .map(Workspace::getName)
                .map(name -> name.length() > UserNotification.MAX_SUBJECT_LENGTH
                        ? name.substring(0, UserNotification.MAX_SUBJECT_LENGTH) : name)
                .orElse(null);
    }

    /** Le titre neutre du catalogue ; un code inconnu (version antérieure) se montre tel quel. */
    static String title(String event) {
        try {
            return PushEvent.valueOf(event).title();
        } catch (IllegalArgumentException | NullPointerException e) {
            return event;
        }
    }
}
