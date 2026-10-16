package fr.claudegateway.notifications;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionFollowUp;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;

/**
 * <b>Le récapitulatif quotidien des attentes</b> (F-185 / SF-185-07) : chaque compte qui a au moins
 * une attente « Demandé » dont la relance est due (règle F-175 : {@link TerminalActionFollowUp})
 * reçoit « Des attentes sont à relancer », au plus une fois par jour.
 *
 * <p>La lecture des attentes est transverse (tâche de fond), mais <b>chaque émission</b> porte le
 * {@code user_id} de l'attente : rien d'un compte ne part vers un autre. La notification passe par
 * l'émetteur commun : centre de notifications, sourdine, heures calmes.</p>
 */
@Service
public class AttentesDigestService {

    private static final Logger log = LoggerFactory.getLogger(AttentesDigestService.class);

    /** Le jour du récapitulatif se compte à l'heure de Paris, comme la planification. */
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    static final int RETENTION_DAYS = 30;
    /** Un accès qui expire dans ces jours (avant ou après aujourd'hui) est signalé (F-180 / SF-180-06). */
    static final int EXPIRY_WINDOW_DAYS = 7;

    /** Le registre des accès (F-180 / SF-180-06) ; absent = seules relances et échéances. */
    private fr.claudegateway.atelier.actions.AccesObtenuRepository acces;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAcces(fr.claudegateway.atelier.actions.AccesObtenuRepository acces) {
        this.acces = acces;
    }

    private final TerminalActionRepository actions;
    private final TerminalActionFollowUp followUp;
    private final NotificationDigestWriter digests;
    private final PushNotificationService push;
    private final Clock clock;

    public AttentesDigestService(TerminalActionRepository actions, TerminalActionFollowUp followUp,
            NotificationDigestWriter digests, PushNotificationService push, Clock clock) {
        this.actions = actions;
        this.followUp = followUp;
        this.digests = digests;
        this.push = push;
        this.clock = clock;
    }

    /** Lance le récapitulatif du jour. Rend le nombre de comptes prévenus par CE passage. */
    public int run() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = now.atZoneSameInstant(ZONE).toLocalDate();
        Set<UUID> due = new LinkedHashSet<>();
        for (TerminalAction action : actions.findByStatus(TerminalActionStatus.DEMANDE)) {
            if (action.getUserId() != null && followUp.isDue(action, now)) {
                due.add(action.getUserId());
            }
        }
        // F-180 / SF-180-06 : une échéance atteinte, un accès qui arrive à terme — le point du matin.
        Set<UUID> morning = new LinkedHashSet<>();
        for (TerminalAction action : actions.findByStatusInAndDueAtLessThanEqual(
                TerminalActionStatus.OPEN_STATES, today)) {
            if (action.getUserId() != null) {
                morning.add(action.getUserId());
            }
        }
        if (acces != null) {
            for (fr.claudegateway.atelier.actions.AccesObtenu entry : acces.findByExpiresAtBetween(
                    today.minusDays(EXPIRY_WINDOW_DAYS), today.plusDays(EXPIRY_WINDOW_DAYS))) {
                if (entry.getUserId() != null) {
                    morning.add(entry.getUserId());
                }
            }
        }
        Set<UUID> everyone = new LinkedHashSet<>(due);
        everyone.addAll(morning);
        int sent = 0;
        for (UUID userId : everyone) {
            try {
                if (digests.claim(userId, today, now)) {
                    // Une seule notification par jour : elle dit le cas dominant.
                    push.notify(userId, null, morning.contains(userId)
                            ? PushEvent.ATTENTES_POINT_DU_MATIN : PushEvent.ATTENTES_TO_FOLLOW_UP);
                    sent++;
                }
            } catch (RuntimeException e) {
                // Un compte en échec n'arrête pas les autres.
                log.warn("Récapitulatif des attentes impossible pour un compte : {}", e.getMessage());
            }
        }
        digests.purgeBefore(today.minusDays(RETENTION_DAYS));
        return sent;
    }
}
