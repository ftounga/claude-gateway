package fr.claudegateway.jarvis.calendar;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.jarvis.mail.MailConfirmation;
import fr.claudegateway.jarvis.portrait.PortraitSeal;

/**
 * <b>Poser un rendez-vous sur un « oui » vérifié côté serveur</b> (F-243 / SF-243-03, D5, D12) — même mécanique que
 * {@code JarvisMailActionService} (F-242 / SF-242-06) : l'outil d'Opus crée l'action {@code PENDING} (10 min), et
 * <b>seul le message suivant de l'utilisateur dans ce fil Jarvis</b>, s'il est un « oui » de la liste fermée
 * ({@link MailConfirmation}), la fait exécuter. Ni un texte du modèle, ni un résultat d'outil, ni un courriel ne
 * confirment. Le serveur pose <b>exactement</b> les champs de l'action (scellés), avec {@code sendUpdates=none} et
 * aucun invité.
 */
@Service
public class JarvisCalendarActionService {

    private static final Logger log = LoggerFactory.getLogger(JarvisCalendarActionService.class);

    public static final Duration TTL = Duration.ofMinutes(10);
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    static final String SIGNATURE = "Posé par Jarvis.";

    /** Ce qui sera posé (heures locales de Paris, ISO sans fuseau). */
    public record Payload(String title, String start, String end, String location, String description,
            String mailItemId) {
    }

    private final JarvisCalendarActionRepository actions;
    private final JarvisCalendarGrantService grants;
    private final JarvisCalendarGrantRepository grantRepository;
    private final GoogleCalendarApi google;
    private final PortraitSeal seal;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public JarvisCalendarActionService(JarvisCalendarActionRepository actions, JarvisCalendarGrantService grants,
            JarvisCalendarGrantRepository grantRepository, GoogleCalendarApi google, PortraitSeal seal, Clock clock) {
        this.actions = actions;
        this.grants = grants;
        this.grantRepository = grantRepository;
        this.google = google;
        this.seal = seal;
        this.clock = clock;
    }

    /** Propose une pose : la précédente en attente de ce fil est annulée (une seule question à la fois). */
    public JarvisCalendarAction propose(UUID userId, UUID workspaceId, JarvisCalendarGrant grant,
            JarvisCalendarAction.Kind kind, Payload payload, UUID mailItemId) {
        actions.findFirstByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtDesc(userId, workspaceId,
                JarvisCalendarAction.Status.PENDING).ifPresent(previous -> actions.close(previous.getId(), userId,
                        JarvisCalendarAction.Status.CANCELLED));
        OffsetDateTime now = OffsetDateTime.now(clock);
        JarvisCalendarAction a = new JarvisCalendarAction();
        a.setUserId(userId);
        a.setWorkspaceId(workspaceId);
        a.setGrantId(grant.getId());
        a.setKind(kind);
        a.setMailItemId(mailItemId);
        a.setPayload(seal.seal(write(payload)));
        String summary = summary(payload);
        a.setSummary(summary.length() <= 600 ? summary : summary.substring(0, 600));
        a.setStatus(JarvisCalendarAction.Status.PENDING);
        a.setCreatedAt(now);
        a.setExpiresAt(now.plus(TTL));
        return actions.save(a);
    }

    /**
     * Le message de l'utilisateur dans le fil Jarvis : s'il suit une action en attente, il la confirme (« oui » net) ou
     * l'annule (tout le reste). Rend la note jointe au message d'Opus, ou {@code ""} sans action en attente.
     */
    public String onUserMessage(UUID userId, UUID workspaceId, UUID messageId, String text) {
        if (userId == null || workspaceId == null) {
            return "";
        }
        JarvisCalendarAction pending = actions.findFirstByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtDesc(userId,
                workspaceId, JarvisCalendarAction.Status.PENDING).orElse(null);
        if (pending == null) {
            return "";
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!pending.getExpiresAt().isAfter(now)) {
            actions.close(pending.getId(), userId, JarvisCalendarAction.Status.EXPIRED);
            return "[Agenda — la pose en attente (« " + pending.getSummary() + " ») a expiré sans « oui » : rien n'est "
                    + "posé. Propose-la à nouveau si l'utilisateur le veut.]";
        }
        if (MailConfirmation.classify(text) != MailConfirmation.Answer.OUI) {
            actions.close(pending.getId(), userId, JarvisCalendarAction.Status.CANCELLED);
            return "[Agenda — pas de « oui » net de l'utilisateur : « " + pending.getSummary() + " » n'est pas posé. "
                    + "Traite son message tel quel.]";
        }
        if (actions.confirm(pending.getId(), userId, messageId, now) != 1) {
            return "";
        }
        return "[Agenda — confirmé par l'utilisateur : " + execute(pending) + "]";
    }

    /** Exécute une action confirmée (une seule fois). */
    String execute(JarvisCalendarAction action) {
        JarvisCalendarAction a = actions.findById(action.getId()).orElse(action);
        try {
            JarvisCalendarGrant grant = grantRepository.findByIdAndUserId(a.getGrantId(), a.getUserId())
                    .orElseThrow(JarvisCalendarGrantService.NoGrantException::new);
            Payload p = read(seal.open(a.getPayload()));
            String done = switch (a.getKind()) {
                case CREATE -> {
                    GoogleCalendarApi.EventBody body = new GoogleCalendarApi.EventBody(p.title(), p.start(), p.end(),
                            ZONE.getId(), p.location(), description(p));
                    String eventId = grants.withAccess(grant, token -> google.insertEvent(token, body));
                    a.setGoogleEventId(eventId);
                    yield "posé — " + a.getSummary();
                }
                case ACCEPT, DECLINE -> throw new IllegalStateException("réponse à une invitation : SF-243-04");
            };
            a.setStatus(JarvisCalendarAction.Status.EXECUTED);
            a.setExecutedAt(OffsetDateTime.now(clock));
            actions.save(a);
            return done;
        } catch (RuntimeException e) {
            log.info("Agenda : pose en échec ({})", e.getClass().getSimpleName());
            a.setStatus(JarvisCalendarAction.Status.FAILED);
            a.setError(e instanceof JarvisCalendarGrantService.NoGrantException ? "Agenda pro non relié."
                    : e instanceof GoogleCalendarApi.GoogleUnavailableException g ? clip(g.getMessage()) : "échec");
            actions.save(a);
            return "ÉCHEC, rien n'est posé (" + a.getError() + ") — " + a.getSummary();
        }
    }

    /** La description posée : celle dite, puis « Posé par Jarvis. » (et la trace du courrier d'origine). */
    static String description(Payload p) {
        StringBuilder d = new StringBuilder();
        if (p.description() != null && !p.description().isBlank()) {
            d.append(p.description().strip()).append("\n\n");
        }
        d.append(SIGNATURE);
        if (p.mailItemId() != null && !p.mailItemId().isBlank()) {
            d.append(" D'après un courriel reçu (courriel:").append(p.mailItemId()).append(").");
        }
        return d.toString();
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);

    /** La reformulation dite avant le « oui » : jour, heures, titre, agenda, lieu — calculée par le serveur. */
    static String summary(Payload p) {
        LocalDateTime start = LocalDateTime.parse(p.start());
        LocalDateTime end = LocalDateTime.parse(p.end());
        String day = DAY.format(start);
        day = Character.toUpperCase(day.charAt(0)) + day.substring(1);
        String s = day + ", " + hour(start) + " – " + hour(end) + (start.toLocalDate().equals(end.toLocalDate()) ? ""
                : " le " + DAY.format(end)) + ", « " + p.title() + " », dans votre agenda pro";
        if (p.location() != null && !p.location().isBlank()) {
            s += " (lieu : " + p.location().strip() + ")";
        }
        return s + ".";
    }

    private static String hour(LocalDateTime t) {
        return t.getMinute() == 0 ? t.getHour() + " h" : t.getHour() + " h " + String.format("%02d", t.getMinute());
    }

    private String write(Payload p) {
        try {
            return json.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("action illisible", e);
        }
    }

    private Payload read(String raw) {
        try {
            return json.readValue(raw, Payload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("action illisible", e);
        }
    }

    private static String clip(String s) {
        return s == null ? null : s.length() <= 300 ? s : s.substring(0, 300);
    }
}
