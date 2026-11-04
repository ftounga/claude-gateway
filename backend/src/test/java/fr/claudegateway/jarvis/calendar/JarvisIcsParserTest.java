package fr.claudegateway.jarvis.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * F-243 / SF-243-01 — l'analyse iCal : simple, journée entière, récurrence hebdomadaire à travers le passage à l'heure
 * d'hiver, {@code EXDATE}, occurrence modifiée, annulation, fuseau étranger, heure flottante, fenêtre, illisible.
 */
class JarvisIcsParserTest {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    static final String VTIMEZONE_PARIS = """
            BEGIN:VTIMEZONE
            TZID:Europe/Paris
            X-LIC-LOCATION:Europe/Paris
            BEGIN:DAYLIGHT
            TZOFFSETFROM:+0100
            TZOFFSETTO:+0200
            TZNAME:CEST
            DTSTART:19700329T020000
            RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
            END:DAYLIGHT
            BEGIN:STANDARD
            TZOFFSETFROM:+0200
            TZOFFSETTO:+0100
            TZNAME:CET
            DTSTART:19701025T030000
            RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
            END:STANDARD
            END:VTIMEZONE
            """;

    static byte[] ics(String events) {
        String body = "BEGIN:VCALENDAR\nPRODID:-//Google Inc//Google Calendar 70.9054//EN\nVERSION:2.0\n"
                + "CALSCALE:GREGORIAN\nX-WR-TIMEZONE:Europe/Paris\n" + VTIMEZONE_PARIS + events + "END:VCALENDAR\n";
        return body.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    static Instant paris(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, PARIS).toInstant();
    }

    static List<JarvisIcsParser.Occurrence> parse(byte[] body, Instant from, Instant to) {
        return JarvisIcsParser.parse(body, from, to, PARIS).occurrences();
    }

    @Test
    void simple_event_with_tzid() {
        byte[] body = ics("""
                BEGIN:VEVENT
                DTSTART;TZID=Europe/Paris:20261105T140000
                DTEND;TZID=Europe/Paris:20261105T150000
                UID:devis-helios@google.com
                SUMMARY:Point devis Helios
                LOCATION:Visio
                DESCRIPTION:Revoir le devis
                STATUS:CONFIRMED
                END:VEVENT
                """);
        List<JarvisIcsParser.Occurrence> out = parse(body, paris(2026, 11, 4, 0, 0), paris(2026, 11, 10, 0, 0));
        assertThat(out).hasSize(1);
        JarvisIcsParser.Occurrence o = out.get(0);
        assertThat(o.start()).isEqualTo(paris(2026, 11, 5, 14, 0));
        assertThat(o.end()).isEqualTo(paris(2026, 11, 5, 15, 0));
        assertThat(o.allDay()).isFalse();
        assertThat(o.uid()).isEqualTo("devis-helios@google.com");
        assertThat(o.recurrenceId()).isNull();
        assertThat(o.text().title()).isEqualTo("Point devis Helios");
        assertThat(o.text().location()).isEqualTo("Visio");
        assertThat(o.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void weekly_nine_oclock_paris_across_the_winter_time_change() {
        // Lundis 19/10 et 26/10/2026 : le passage à l'heure d'hiver a lieu le dimanche 25/10.
        byte[] body = ics("""
                BEGIN:VEVENT
                DTSTART;TZID=Europe/Paris:20261005T090000
                DTEND;TZID=Europe/Paris:20261005T093000
                RRULE:FREQ=WEEKLY;BYDAY=MO
                EXDATE;TZID=Europe/Paris:20261012T090000
                UID:hebdo@google.com
                SUMMARY:Point hebdo
                END:VEVENT
                """);
        List<JarvisIcsParser.Occurrence> out = parse(body, paris(2026, 10, 1, 0, 0), paris(2026, 11, 3, 0, 0));
        assertThat(out).extracting(JarvisIcsParser.Occurrence::start).containsExactly(
                paris(2026, 10, 5, 9, 0), paris(2026, 10, 19, 9, 0), paris(2026, 10, 26, 9, 0),
                paris(2026, 11, 2, 9, 0));
        // 9 h à Paris des deux côtés du changement d'heure : 07:00Z puis 08:00Z.
        assertThat(out.get(1).start()).isEqualTo(Instant.parse("2026-10-19T07:00:00Z"));
        assertThat(out.get(2).start()).isEqualTo(Instant.parse("2026-10-26T08:00:00Z"));
        assertThat(out.get(2).end()).isEqualTo(paris(2026, 10, 26, 9, 30));
        assertThat(out).allSatisfy(o -> assertThat(o.recurrenceId()).isNotNull());
        assertThat(out.stream().map(o -> o.start().atZone(PARIS).toLocalTime()).distinct().toList())
                .containsExactly(LocalTime.of(9, 0));
    }

    @Test
    void modified_and_cancelled_occurrences() {
        byte[] body = ics("""
                BEGIN:VEVENT
                DTSTART;TZID=Europe/Paris:20261102T100000
                DTEND;TZID=Europe/Paris:20261102T110000
                RRULE:FREQ=DAILY;COUNT=4
                UID:daily@google.com
                SUMMARY:Stand-up
                END:VEVENT
                BEGIN:VEVENT
                DTSTART;TZID=Europe/Paris:20261103T160000
                DTEND;TZID=Europe/Paris:20261103T170000
                RECURRENCE-ID;TZID=Europe/Paris:20261103T100000
                UID:daily@google.com
                SUMMARY:Stand-up décalé
                END:VEVENT
                BEGIN:VEVENT
                DTSTART;TZID=Europe/Paris:20261104T100000
                DTEND;TZID=Europe/Paris:20261104T110000
                RECURRENCE-ID;TZID=Europe/Paris:20261104T100000
                UID:daily@google.com
                STATUS:CANCELLED
                SUMMARY:Stand-up
                END:VEVENT
                BEGIN:VEVENT
                DTSTART;TZID=Europe/Paris:20261106T100000
                DTEND;TZID=Europe/Paris:20261106T110000
                UID:annule@google.com
                STATUS:CANCELLED
                SUMMARY:Annulé
                END:VEVENT
                """);
        List<JarvisIcsParser.Occurrence> out = parse(body, paris(2026, 11, 1, 0, 0), paris(2026, 11, 10, 0, 0));
        assertThat(out).extracting(JarvisIcsParser.Occurrence::start).containsExactly(
                paris(2026, 11, 2, 10, 0), paris(2026, 11, 3, 16, 0), paris(2026, 11, 5, 10, 0));
        assertThat(out.get(1).text().title()).isEqualTo("Stand-up décalé");
        assertThat(out.get(1).recurrenceId()).isEqualTo(paris(2026, 11, 3, 10, 0).toString());
    }

    @Test
    void all_day_events_and_floating_times() {
        byte[] body = ics("""
                BEGIN:VEVENT
                DTSTART;VALUE=DATE:20261111
                DTEND;VALUE=DATE:20261112
                UID:ferie@google.com
                SUMMARY:Armistice
                END:VEVENT
                BEGIN:VEVENT
                DTSTART;VALUE=DATE:20261120
                DTEND;VALUE=DATE:20261123
                UID:conges@google.com
                SUMMARY:Congés
                END:VEVENT
                BEGIN:VEVENT
                DTSTART:20261112T083000
                DTEND:20261112T090000
                UID:flottant@google.com
                SUMMARY:Café
                END:VEVENT
                """);
        List<JarvisIcsParser.Occurrence> out = parse(body, paris(2026, 11, 10, 0, 0), paris(2026, 11, 30, 0, 0));
        assertThat(out).hasSize(3);
        JarvisIcsParser.Occurrence armistice = out.get(0);
        assertThat(armistice.allDay()).isTrue();
        assertThat(armistice.start()).isEqualTo(LocalDate.of(2026, 11, 11).atStartOfDay(PARIS).toInstant());
        assertThat(armistice.end()).isEqualTo(LocalDate.of(2026, 11, 12).atStartOfDay(PARIS).toInstant());
        assertThat(out.get(1).start()).isEqualTo(paris(2026, 11, 12, 8, 30));
        assertThat(out.get(2).end()).isEqualTo(LocalDate.of(2026, 11, 23).atStartOfDay(PARIS).toInstant());
    }

    @Test
    void utc_and_foreign_zone() {
        byte[] body = ics("""
                BEGIN:VEVENT
                DTSTART:20261105T130000Z
                DTEND:20261105T140000Z
                UID:utc@google.com
                SUMMARY:UTC
                END:VEVENT
                BEGIN:VEVENT
                DTSTART;TZID=America/New_York:20261105T090000
                DTEND;TZID=America/New_York:20261105T100000
                UID:ny@google.com
                SUMMARY:New York
                END:VEVENT
                """);
        List<JarvisIcsParser.Occurrence> out = parse(body, paris(2026, 11, 4, 0, 0), paris(2026, 11, 6, 0, 0));
        assertThat(out).extracting(JarvisIcsParser.Occurrence::start).containsExactly(
                Instant.parse("2026-11-05T13:00:00Z"), Instant.parse("2026-11-05T14:00:00Z"));
    }

    @Test
    void windows_zone_name_falls_back_to_the_default_zone() {
        byte[] body = ics("""
                BEGIN:VEVENT
                DTSTART;TZID=Romance Standard Time:20261105T140000
                DTEND;TZID=Romance Standard Time:20261105T150000
                UID:outlook@example.com
                SUMMARY:Outlook
                END:VEVENT
                """);
        assertThat(parse(body, paris(2026, 11, 4, 0, 0), paris(2026, 11, 6, 0, 0)))
                .extracting(JarvisIcsParser.Occurrence::start).containsExactly(paris(2026, 11, 5, 14, 0));
    }

    @Test
    void only_the_window_and_recurrence_started_long_ago() {
        byte[] body = ics("""
                BEGIN:VEVENT
                DTSTART;TZID=Europe/Paris:20200106T090000
                DTEND;TZID=Europe/Paris:20200106T100000
                RRULE:FREQ=WEEKLY
                UID:ancien@google.com
                SUMMARY:Ancien hebdo
                END:VEVENT
                BEGIN:VEVENT
                DTSTART;TZID=Europe/Paris:20250101T090000
                DTEND;TZID=Europe/Paris:20250101T100000
                UID:passe@google.com
                SUMMARY:Passé
                END:VEVENT
                """);
        List<JarvisIcsParser.Occurrence> out = parse(body, paris(2026, 11, 2, 0, 0), paris(2026, 11, 16, 0, 0));
        assertThat(out).extracting(JarvisIcsParser.Occurrence::start).containsExactly(
                paris(2026, 11, 2, 9, 0), paris(2026, 11, 9, 9, 0));
    }

    @Test
    void event_in_progress_at_window_start_is_kept() {
        byte[] body = ics("""
                BEGIN:VEVENT
                DTSTART;TZID=Europe/Paris:20261103T230000
                DTEND;TZID=Europe/Paris:20261104T010000
                UID:nuit@google.com
                SUMMARY:Nuit
                END:VEVENT
                """);
        assertThat(parse(body, paris(2026, 11, 4, 0, 0), paris(2026, 11, 5, 0, 0))).hasSize(1);
    }

    @Test
    void method_and_text_bounds() {
        String longTitle = "x".repeat(400);
        byte[] body = ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:test\r\nMETHOD:REQUEST\r\nBEGIN:VEVENT\r\n"
                + "DTSTART:20261105T130000Z\r\nDTEND:20261105T140000Z\r\nUID:x\r\nSUMMARY:" + longTitle
                + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n").getBytes(StandardCharsets.UTF_8);
        JarvisIcsParser.Parsed parsed = JarvisIcsParser.parse(body, paris(2026, 11, 4, 0, 0),
                paris(2026, 11, 6, 0, 0), PARIS);
        assertThat(parsed.method()).isEqualTo("REQUEST");
        assertThat(parsed.occurrences().get(0).text().title()).hasSizeLessThanOrEqualTo(EventText.MAX_TITLE);
    }

    @Test
    void unreadable_body_is_refused() {
        assertThatThrownBy(() -> JarvisIcsParser.parse("<html>nope</html>".getBytes(StandardCharsets.UTF_8),
                Instant.now(), Instant.now().plusSeconds(60), PARIS))
                .isInstanceOf(JarvisIcsParser.InvalidCalendarException.class);
        assertThatThrownBy(() -> JarvisIcsParser.parse(new byte[0], Instant.now(), Instant.now(), PARIS))
                .isInstanceOf(JarvisIcsParser.InvalidCalendarException.class);
    }
}
