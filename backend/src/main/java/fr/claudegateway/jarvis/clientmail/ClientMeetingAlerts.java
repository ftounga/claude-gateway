package fr.claudegateway.jarvis.clientmail;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import fr.claudegateway.jarvis.calendar.EventText;
import fr.claudegateway.jarvis.calendar.JarvisCalendarEvent;
import fr.claudegateway.jarvis.calendar.JarvisCalendarEventRepository;
import fr.claudegateway.jarvis.calendar.JarvisCalendarFeedRepository;
import fr.claudegateway.jarvis.calendar.JarvisCalendarStore;
import fr.claudegateway.jarvis.notices.JarvisNotice;
import fr.claudegateway.jarvis.notices.JarvisNoticeService;
import fr.claudegateway.jarvis.notices.NoticeTexts;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * <b>Les alertes avant réunion</b> (F-249 / SF-249-04, D15) — chaque minute : les réunions (flux iCal F-243 <b>et</b>
 * réunions des clients) qui commencent dans le délai réglé du compte (défaut 10 min, 0 = coupé), hors journée entière
 * et annulées, <b>une seule fois</b> par ({@code UID}, début) : annonce « Réunion « Point Fortec » (EDENRED) dans
 * 10 min » et notification F-185 neutre. Chaque ligne est lue et annoncée sous son {@code user_id}.
 */
@Component
public class ClientMeetingAlerts implements fr.claudegateway.jarvis.mail.JarvisMailPurge {

    private static final Logger log = LoggerFactory.getLogger(ClientMeetingAlerts.class);
    /** Le plus grand délai réglable : la fenêtre balayée. */
    static final int MAX_LEAD = 60;

    private final ClientMeetingRepository meetings;
    private final ClientMeetingService meetingService;
    private final JarvisCalendarEventRepository events;
    private final JarvisCalendarFeedRepository feeds;
    private final JarvisCalendarStore store;
    private final MeetingAlertRepository alerts;
    private final ClientMailSettingsService settings;
    private final RunnerHostRepository hosts;
    private final JarvisNoticeService notices;
    private final Clock clock;

    private PushNotificationService push;

    public ClientMeetingAlerts(ClientMeetingRepository meetings, ClientMeetingService meetingService,
            JarvisCalendarEventRepository events, JarvisCalendarFeedRepository feeds, JarvisCalendarStore store,
            MeetingAlertRepository alerts, ClientMailSettingsService settings, RunnerHostRepository hosts,
            JarvisNoticeService notices, Clock clock) {
        this.meetings = meetings;
        this.meetingService = meetingService;
        this.events = events;
        this.feeds = feeds;
        this.store = store;
        this.alerts = alerts;
        this.settings = settings;
        this.hosts = hosts;
        this.notices = notices;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setPush(PushNotificationService push) {
        this.push = push;
    }

    @Scheduled(fixedDelayString = "${app.jarvis.client-mail.alerts-tick:60000}",
            initialDelayString = "${app.jarvis.client-mail.alerts-initial-delay:75000}")
    public void tick() {
        if (!notices.enabled()) {
            return;
        }
        try {
            run();
        } catch (RuntimeException e) {
            log.warn("Alertes avant réunion : passage reporté ({})", e.getClass().getSimpleName());
        }
    }

    /** Un passage ; rend le nombre d'alertes données. */
    public int run() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime horizon = now.plusMinutes(MAX_LEAD);
        Map<UUID, Integer> leads = new HashMap<>();
        int given = 0;
        for (ClientMeeting m : meetings.startingBetween(now, horizon)) {
            if ("CANCELLED".equalsIgnoreCase(m.getStatus())) {
                continue;
            }
            String client = hosts.findByIdAndUserId(m.getHostId(), m.getUserId()).map(h -> h.getName()).orElse("");
            EventText t = meetingService.text(m);
            if (alert(m.getUserId(), m.getIcalUid(), m.getStartsAt(), t.title(), client, now, leads)) {
                given++;
            }
        }
        for (JarvisCalendarEvent e : events.startingBetween(now, horizon)) {
            if (e.isAllDay() || "CANCELLED".equalsIgnoreCase(e.getStatus())) {
                continue;
            }
            String label = feeds.findById(e.getFeedId()).filter(f -> f.getUserId().equals(e.getUserId()))
                    .map(f -> f.getLabel()).orElse("");
            if (alert(e.getUserId(), e.getIcalUid(), e.getStartsAt(), store.open(e).title(), label, now, leads)) {
                given++;
            }
        }
        return given;
    }

    private boolean alert(UUID userId, String uid, OffsetDateTime start, String title, String where,
            OffsetDateTime now, Map<UUID, Integer> leads) {
        int lead = leads.computeIfAbsent(userId, id -> settings.of(id).getMeetingAlertMinutes());
        if (lead <= 0 || start.isAfter(now.plusMinutes(lead))) {
            return false;
        }
        String key = uid + "|" + start.toInstant();
        if (alerts.existsByUserIdAndAlertKey(userId, key)) {
            return false;
        }
        MeetingAlert a = new MeetingAlert();
        a.setUserId(userId);
        a.setAlertKey(key.length() <= 600 ? key : key.substring(0, 600));
        a.setCreatedAt(now);
        try {
            alerts.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            return false; // donnée ailleurs au même instant
        }
        long minutes = Math.max(1, Math.round(java.time.Duration.between(now, start).toSeconds() / 60.0));
        String name = NoticeTexts.name(title == null || title.isBlank() ? "Réunion" : title);
        String w = NoticeTexts.name(where);
        String display = "Réunion « " + name + " »" + (w.isEmpty() ? "" : " (" + w + ")") + " dans " + minutes + " min";
        String speech = "« " + name + " » commence dans " + NoticeTexts.word((int) minutes).toLowerCase(java.util.Locale.ROOT)
                + (minutes == 1 ? " minute." : " minutes.");
        notices.upsert(userId, JarvisNotice.MEETING_SOON, "meeting:" + key.hashCode() + ":" + start.toEpochSecond(),
                null, null, 1, false, n -> new JarvisNoticeService.Texts(display, speech), "/jarvis", start);
        if (push != null) {
            push.notify(userId, null, PushEvent.MEETING_SOON, "/jarvis");
        }
        return true;
    }

    /** Les alertes de plus de 2 jours, chaque nuit. */
    @Scheduled(cron = "${app.jarvis.client-mail.alerts-purge-cron:0 56 3 * * *}", zone = "Europe/Paris")
    public void purgeOld() {
        alerts.purgeBefore(OffsetDateTime.now(clock).minusDays(2));
    }

    @Override
    public void purgeAccount(UUID userId, UUID accountId) {
        // sans objet
    }

    @Override
    public void purgeUser(UUID userId) {
        alerts.purgeUser(userId);
    }
}
