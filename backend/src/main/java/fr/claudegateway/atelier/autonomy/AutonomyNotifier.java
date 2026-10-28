package fr.claudegateway.atelier.autonomy;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;

/**
 * <b>La course autonome rend compte par notification</b> (F-219 / SF-219-02) — catalogue F-185, titres et
 * corps <b>neutres</b> (aucun nom de chantier ne quitte l'application), sonnerie F-217 pour ce qui attend un
 * geste. Une notification par course ; aucune quand l'utilisateur a lui-même repris la main. Best-effort.
 */
@Component
public class AutonomyNotifier {

    private PushNotificationService push;

    @Autowired(required = false)
    public void setPushNotificationService(PushNotificationService push) {
        this.push = push;
    }

    /** L'événement d'une issue, ou {@code null} quand rien n'est à notifier. */
    public static PushEvent eventFor(AutonomyRunStatus status, boolean byUser) {
        if (byUser) {
            return null;
        }
        return switch (status) {
            case DONE -> PushEvent.AUTONOMY_DONE;
            case BLOCKED, PARTIAL, STOPPED, FAILED -> PushEvent.AUTONOMY_STOPPED;
            case RUNNING -> null;
        };
    }

    /** Notifie l'issue d'une course à son propriétaire. */
    public void notify(UUID userId, UUID workspaceId, AutonomyRunStatus status, boolean byUser) {
        PushEvent event = eventFor(status, byUser);
        if (push == null || event == null) {
            return;
        }
        try {
            push.notify(userId, workspaceId, event);
        } catch (RuntimeException e) {
            // Best-effort : une notification ne fait jamais échouer une course.
        }
    }
}
