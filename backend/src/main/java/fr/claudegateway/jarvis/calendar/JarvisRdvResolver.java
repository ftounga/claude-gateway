package fr.claudegateway.jarvis.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import fr.claudegateway.jarvis.mail.MailRdv;

/**
 * <b>Résoudre une date écrite</b> (F-243 / SF-243-04, D7) — <b>déterministe</b>, par rapport à la <b>date du
 * courriel</b>, en Europe/Paris : « jeudi 14 h » dans un courriel du mardi 3 = jeudi 5 à 14 h. Un jour de la semaine
 * désigne le <b>prochain</b> après le jour du courriel (le même jour de la semaine désigne la semaine suivante). Une
 * proposition sans certitude haute, sans heure, ou dont la date contredit le jour dit, ne donne <b>aucun</b> créneau :
 * Jarvis demande le créneau au lieu de le deviner.
 */
public final class JarvisRdvResolver {

    static final int DEFAULT_MINUTES = 60;
    static final int HORIZON_DAYS = 120;
    private static final List<String> WEEKDAYS = List.of("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi",
            "dimanche");

    /** Un créneau résolu, heure de Paris. */
    public record Slot(LocalDateTime start, LocalDateTime end) {
    }

    private JarvisRdvResolver() {
    }

    public static Optional<Slot> resolve(MailRdv rdv, OffsetDateTime receivedAt, ZoneId zone) {
        if (rdv == null || !rdv.certain() || rdv.hour().isEmpty() || receivedAt == null) {
            return Optional.empty();
        }
        LocalDate mailDay = receivedAt.atZoneSameInstant(zone).toLocalDate();
        LocalDate fromWeekday = null;
        int w = WEEKDAYS.indexOf(rdv.weekday().toLowerCase(Locale.ROOT));
        if (w >= 0) {
            fromWeekday = mailDay.with(TemporalAdjusters.next(DayOfWeek.of(w + 1)));
        }
        LocalDate fromDate = null;
        if (!rdv.date().isEmpty()) {
            try {
                fromDate = LocalDate.parse(rdv.date());
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }
        LocalDate day;
        if (fromDate != null && fromWeekday != null) {
            if (fromDate.getDayOfWeek() != DayOfWeek.of(w + 1)) {
                return Optional.empty(); // la date et le jour se contredisent : on demande
            }
            day = fromDate;
        } else if (fromDate != null) {
            day = fromDate;
        } else if (fromWeekday != null) {
            day = fromWeekday;
        } else {
            return Optional.empty();
        }
        if (day.isBefore(mailDay) || day.isAfter(mailDay.plusDays(HORIZON_DAYS))) {
            return Optional.empty();
        }
        LocalTime time;
        try {
            String[] hm = rdv.hour().split(":");
            time = LocalTime.of(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        LocalDateTime start = day.atTime(time);
        int minutes = rdv.minutes() > 0 ? rdv.minutes() : DEFAULT_MINUTES;
        return Optional.of(new Slot(start, start.plusMinutes(minutes)));
    }
}
