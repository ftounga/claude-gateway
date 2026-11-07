package fr.claudegateway.jarvis.clientmail;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.jarvis.calendar.ClientMeetingSource;
import fr.claudegateway.jarvis.calendar.EventText;
import fr.claudegateway.jarvis.calendar.JarvisCalendarEvent;
import fr.claudegateway.jarvis.calendar.JarvisCalendarReader;
import fr.claudegateway.jarvis.calendar.JarvisCalendarStore;
import fr.claudegateway.jarvis.calendar.JarvisIcsParser;
import fr.claudegateway.jarvis.mail.JarvisMailPurge;
import fr.claudegateway.runner.host.RunnerHostLifecycleEvent;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * <b>Les réunions des clients</b> (F-249 / SF-249-04, D14) — l'invitation d'un mail déposé, lue sans IA par
 * {@link JarvisIcsParser}, devient des occurrences de l'agenda tous clients (J-1 → J+60), <b>sans « oui »</b> : rien
 * n'est écrit chez Google, c'est le reflet de l'Outlook du client. {@code CANCEL} retire ; une version plus récente
 * du même {@code UID} remplace, une plus ancienne est ignorée. Les invités ne sont jamais lus.
 */
@Service
public class ClientMeetingService implements ClientMeetingSource, JarvisMailPurge {

    private static final Logger log = LoggerFactory.getLogger(ClientMeetingService.class);
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    static final Duration PAST = Duration.ofDays(1);
    static final Duration FUTURE = Duration.ofDays(60);

    private final ClientMeetingRepository meetings;
    private final JarvisCalendarStore store;
    private final RunnerHostRepository hosts;
    private final Clock clock;

    public ClientMeetingService(ClientMeetingRepository meetings, JarvisCalendarStore store, RunnerHostRepository hosts,
            Clock clock) {
        this.meetings = meetings;
        this.store = store;
        this.hosts = hosts;
        this.clock = clock;
    }

    /** Applique une invitation déposée ; rend le nombre d'occurrences écrites (0 pour une annulation). */
    @Transactional
    public int apply(UUID userId, UUID hostId, UUID mailItemId, byte[] ics, OffsetDateTime receivedAt) {
        if (ics == null || ics.length == 0) {
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime received = receivedAt == null ? now : receivedAt;
        java.util.Optional<JarvisIcsParser.Invitation> head = JarvisIcsParser.invitation(ics, ZONE);
        if (head.isPresent() && "CANCEL".equals(head.get().method())) {
            String uid = head.get().uid();
            List<ClientMeeting> existing = meetings.findByUserIdAndIcalUid(userId, uid);
            if (existing.stream().anyMatch(m -> m.getSourceReceivedAt().isAfter(received))) {
                return 0; // une version plus récente a déjà été reçue
            }
            boolean oneOccurrence = new String(ics, java.nio.charset.StandardCharsets.UTF_8).contains("RECURRENCE-ID");
            if (oneOccurrence && head.get().start() != null) {
                // Une seule occurrence annulée : celle qui commence à ce moment-là.
                existing.stream().filter(m -> m.getStartsAt().toInstant().equals(head.get().start()))
                        .forEach(meetings::delete);
            } else {
                meetings.deleteUid(userId, uid);
            }
            return 0;
        }
        JarvisIcsParser.Parsed parsed;
        try {
            parsed = JarvisIcsParser.parse(ics, now.minus(PAST).toInstant(), now.plus(FUTURE).toInstant(), ZONE);
        } catch (RuntimeException e) {
            log.info("Réunions : invitation illisible ({})", e.getClass().getSimpleName());
            return 0;
        }
        if (parsed.occurrences().isEmpty()) {
            return 0;
        }
        String uid = parsed.occurrences().get(0).uid();
        List<ClientMeeting> existing = meetings.findByUserIdAndIcalUid(userId, uid);
        if (existing.stream().anyMatch(m -> m.getSourceReceivedAt().isAfter(received))) {
            return 0; // une version plus ancienne déposée après une plus récente : ignorée
        }
        meetings.deleteUid(userId, uid);
        List<ClientMeeting> rows = new ArrayList<>();
        for (JarvisCalendarStore.SealedOccurrence s : store.seal(parsed.occurrences())) {
            JarvisIcsParser.Occurrence o = s.occurrence();
            if ("CANCELLED".equalsIgnoreCase(o.status())) {
                continue;
            }
            ClientMeeting m = new ClientMeeting();
            m.setUserId(userId);
            m.setHostId(hostId);
            m.setIcalUid(clip(o.uid(), 500));
            m.setRecurrenceId(clip(o.recurrenceId(), 64));
            m.setStartsAt(o.start().atOffset(ZoneOffset.UTC));
            m.setEndsAt(o.end().atOffset(ZoneOffset.UTC));
            m.setAllDay(o.allDay());
            m.setSealed(s.sealed());
            m.setStatus(o.status());
            m.setSourceReceivedAt(received);
            m.setMailItemId(mailItemId);
            m.setCreatedAt(now);
            rows.add(m);
        }
        meetings.saveAll(rows);
        return rows.size();
    }

    /** Le texte descellé d'une réunion. */
    public EventText text(ClientMeeting m) {
        JarvisCalendarEvent e = new JarvisCalendarEvent();
        e.setSealed(m.getSealed());
        return store.open(e);
    }

    /** {@link ClientMeetingSource} — les réunions des clients de [from, to[, pour la vue fusionnée F-243. */
    @Override
    @Transactional(readOnly = true)
    public List<JarvisCalendarReader.Item> between(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        Map<UUID, String> names = new java.util.HashMap<>();
        List<JarvisCalendarReader.Item> out = new ArrayList<>();
        for (ClientMeeting m : meetings.overlapping(userId, from, to)) {
            String client = names.computeIfAbsent(m.getHostId(),
                    id -> hosts.findByIdAndUserId(id, userId).map(h -> h.getName()).orElse(""));
            EventText t = text(m);
            out.add(new JarvisCalendarReader.Item(m.getId(), label(client), m.getIcalUid(), m.getStartsAt(),
                    m.getEndsAt(), m.isAllDay(), t.title(), t.location(), m.getStatus()));
        }
        return out;
    }

    /** « Outlook EDENRED ». */
    public static String label(String client) {
        return client == null || client.isBlank() ? "Outlook" : "Outlook " + client.strip();
    }

    @Override
    public boolean any(UUID userId) {
        return meetings.existsByUserId(userId);
    }

    /** Les réunions finies depuis plus de 7 jours, chaque nuit. */
    @Scheduled(cron = "${app.jarvis.client-mail.meetings-purge-cron:0 59 3 * * *}", zone = "Europe/Paris")
    public void purgeOld() {
        meetings.purgeBefore(OffsetDateTime.now(clock).minusDays(7));
    }

    @EventListener
    public void onHostDeleted(RunnerHostLifecycleEvent event) {
        if (event != null && event.kind() == RunnerHostLifecycleEvent.Kind.DELETED) {
            meetings.purgeHost(event.userId(), event.hostId());
        }
    }

    @Override
    public void purgeAccount(UUID userId, UUID accountId) {
        // sans objet
    }

    @Override
    public void purgeUser(UUID userId) {
        meetings.purgeUser(userId);
    }

    private static String clip(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
