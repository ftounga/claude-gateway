package fr.claudegateway.jarvis.clientmail;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import fr.claudegateway.jarvis.notices.JarvisNotice;
import fr.claudegateway.jarvis.notices.JarvisNoticeService;
import fr.claudegateway.jarvis.notices.NoticeTexts;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * <b>Le rappel du soir</b> (F-249 / SF-249-05, D12) — chaque minute, pour chaque client actif : le n-ième rappel du jour
 * est dû à {@code heure + (n-1) × intervalle} (Paris), {@code max} fois au plus, jours ouvrés si réglé. Dû, pas encore
 * parti, et <b>aucun dépôt</b> ce jour-là pour ce client ⇒ annonce « Déposez vos mails EDENRED » et notification F-185.
 * Le compte du jour avance par {@code UPDATE} conditionnel : deux pods n'envoient jamais le même rappel.
 */
@Component
public class ClientMailReminder {

    private static final Logger log = LoggerFactory.getLogger(ClientMailReminder.class);
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    private final ClientMailHostRepository clientHosts;
    private final ClientMailReminderDayRepository days;
    private final ClientMailSettingsService settings;
    private final RunnerHostRepository hosts;
    private final JarvisNoticeService notices;
    private final ClientMailProperties properties;
    private final Clock clock;

    private PushNotificationService push;

    public ClientMailReminder(ClientMailHostRepository clientHosts, ClientMailReminderDayRepository days,
            ClientMailSettingsService settings, RunnerHostRepository hosts, JarvisNoticeService notices,
            ClientMailProperties properties, Clock clock) {
        this.clientHosts = clientHosts;
        this.days = days;
        this.settings = settings;
        this.hosts = hosts;
        this.notices = notices;
        this.properties = properties;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setPush(PushNotificationService push) {
        this.push = push;
    }

    /** La clé de l'annonce du rappel d'un client pour un jour. */
    public static String key(UUID hostId, LocalDate day) {
        return "reminder:" + hostId + ":" + day;
    }

    /** Combien de rappels sont dus à cet instant (0 avant l'heure, jamais plus que {@code max}). */
    static int due(ZonedDateTime now, LocalTime first, int max, int everyMinutes, boolean weekdaysOnly) {
        if (weekdaysOnly && (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY)) {
            return 0;
        }
        LocalTime t = now.toLocalTime();
        if (t.isBefore(first)) {
            return 0;
        }
        long minutes = java.time.Duration.between(first, t).toMinutes();
        return (int) Math.min(max, minutes / Math.max(1, everyMinutes) + 1);
    }

    @Scheduled(fixedDelayString = "${app.jarvis.client-mail.reminder-tick:60000}",
            initialDelayString = "${app.jarvis.client-mail.reminder-initial-delay:70000}")
    public void tick() {
        if (!properties.isEnabled() || !notices.enabled()) {
            return;
        }
        for (ClientMailHost h : clientHosts.findByReminderEnabledTrue()) {
            try {
                remind(h.getUserId(), h.getHostId());
            } catch (RuntimeException e) {
                log.warn("Rappel du soir : reporté ({})", e.getClass().getSimpleName());
            }
        }
    }

    /** Envoie le rappel dû d'un client, s'il y en a un. Rend vrai si un rappel est parti. */
    public boolean remind(UUID userId, UUID hostId) {
        ClientMailHost row = clientHosts.findByUserIdAndHostId(userId, hostId).orElse(null);
        if (row == null || !row.isReminderEnabled()) {
            return false;
        }
        ClientMailSettings s = settings.of(userId);
        ZonedDateTime now = OffsetDateTime.now(clock).atZoneSameInstant(ZONE);
        int due = due(now, LocalTime.parse(s.getReminderTime()), s.getReminderMax(), s.getReminderEveryMinutes(),
                s.isWeekdaysOnly());
        if (due == 0 || settings.depositedToday(userId, hostId)) {
            return false;
        }
        LocalDate today = now.toLocalDate();
        ClientMailReminderDay day = days.findByUserIdAndHostIdAndReminderDay(userId, hostId, today).orElse(null);
        if (day == null) {
            day = new ClientMailReminderDay();
            day.setUserId(userId);
            day.setHostId(hostId);
            day.setReminderDay(today);
            try {
                day = days.saveAndFlush(day);
            } catch (DataIntegrityViolationException e) {
                day = days.findByUserIdAndHostIdAndReminderDay(userId, hostId, today).orElse(null);
                if (day == null) {
                    return false;
                }
            }
        }
        if (day.getDepositedAt() != null || day.getSentCount() >= due) {
            return false;
        }
        if (days.advance(day.getId(), day.getSentCount(), due, now.toOffsetDateTime()) != 1) {
            return false; // pris ailleurs, ou dépôt arrivé entre-temps
        }
        String client = hosts.findByIdAndUserId(hostId, userId).map(h -> h.getName()).orElse("");
        String name = NoticeTexts.name(client);
        notices.upsert(userId, JarvisNotice.DEPOSIT_REMINDER, key(hostId, today), hostId, null, due, false,
                n -> new JarvisNoticeService.Texts("Déposez vos mails " + name,
                        "Pensez à déposer vos mails " + name + " de la journée."),
                "/jarvis", today.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        if (push != null) {
            push.notify(userId, null, PushEvent.CLIENT_MAIL_DEPOSIT_REMINDER, "/jarvis");
        }
        return true;
    }

    /** Les jours de rappel de plus de 7 jours, chaque nuit. */
    @Scheduled(cron = "${app.jarvis.client-mail.reminder-purge-cron:0 57 3 * * *}", zone = "Europe/Paris")
    public void purgeOld() {
        days.purgeBefore(LocalDate.now(clock.withZone(ZONE)).minusDays(7));
    }
}
