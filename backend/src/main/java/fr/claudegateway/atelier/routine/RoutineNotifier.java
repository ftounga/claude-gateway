package fr.claudegateway.atelier.routine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;

/**
 * <b>La routine rend compte par notification</b> (F-183 / SF-183-04, D4) — via le catalogue F-185 :
 * titres et corps <b>neutres</b>, aucun nom ni contenu ne quitte l'application ; le détail se lit dans
 * le sujet. Une notification par exécution. Best-effort : sans push configuré, l'événement va au centre
 * de notifications (F-185) ; jamais d'erreur.
 */
@Component
public class RoutineNotifier {

    private PushNotificationService push;

    @Autowired(required = false)
    public void setPushNotificationService(PushNotificationService push) {
        this.push = push;
    }

    /** L'événement qui dit l'issue d'une exécution, ou {@code null} pour un tour encore en cours. */
    public static PushEvent eventFor(RoutineRunStatus status) {
        return switch (status) {
            case DONE -> PushEvent.ROUTINE_DONE;
            case MISSED_OFFLINE -> PushEvent.ROUTINE_MISSED;
            case INCOMPLETE, FAILED, INTERRUPTED, SKIPPED_BUDGET -> PushEvent.ROUTINE_STOPPED;
            case RUNNING -> null;
        };
    }

    /** Notifie l'issue d'une exécution au propriétaire de la routine. */
    public void notify(SubjectRoutine routine, RoutineRunStatus status) {
        PushEvent event = eventFor(status);
        if (push == null || event == null || routine == null) {
            return;
        }
        try {
            push.notify(routine.getUserId(), routine.getWorkspaceId(), event);
        } catch (RuntimeException e) {
            // Best-effort : une notification ne fait jamais échouer une routine.
        }
    }
}
