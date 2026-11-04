package fr.claudegateway.jarvis.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.jarvis.mail.JarvisMailItem;
import fr.claudegateway.jarvis.mail.JarvisMailTriageParser;
import fr.claudegateway.jarvis.mail.MailRdv;

/**
 * F-243 / SF-243-04 — la date écrite résolue par rapport à la date du courriel (Europe/Paris), sans rien deviner ; la
 * lecture du champ {@code rdv} du tri ; l'invitation {@code .ics} lue sans jamais lire ses invités.
 */
class JarvisRdvResolverTest {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    /** Mardi 3 novembre 2026, 09:12 à Paris. */
    static final OffsetDateTime TUESDAY = OffsetDateTime.parse("2026-11-03T08:12:00Z");

    static MailRdv rdv(String weekday, String date, String hour, boolean certain) {
        return new MailRdv("Point devis", weekday, date, hour, 0, "", certain);
    }

    @Test
    void thursdayAtTwoInATuesdayMailIsThursdayFifthAtTwoParisTime() {
        Optional<JarvisRdvResolver.Slot> slot = JarvisRdvResolver.resolve(rdv("jeudi", "", "14:00", true), TUESDAY,
                PARIS);
        assertThat(slot).isPresent();
        assertThat(slot.get().start()).isEqualTo(LocalDateTime.parse("2026-11-05T14:00"));
        assertThat(slot.get().end()).isEqualTo(LocalDateTime.parse("2026-11-05T15:00"));
    }

    @Test
    void theSameWeekdayMeansNextWeekAndAnExplicitDateIsKept() {
        assertThat(JarvisRdvResolver.resolve(rdv("mardi", "", "10:30", true), TUESDAY, PARIS).orElseThrow().start())
                .isEqualTo(LocalDateTime.parse("2026-11-10T10:30"));
        assertThat(JarvisRdvResolver.resolve(rdv("", "2026-11-12", "09:00", true), TUESDAY, PARIS).orElseThrow()
                .start()).isEqualTo(LocalDateTime.parse("2026-11-12T09:00"));
        assertThat(JarvisRdvResolver.resolve(rdv("jeudi", "2026-11-12", "09:00", true), TUESDAY, PARIS)).isPresent();
    }

    @Test
    void nothingIsGuessed() {
        // « La semaine prochaine ? » : certitude basse.
        assertThat(JarvisRdvResolver.resolve(rdv("", "", "", false), TUESDAY, PARIS)).isEmpty();
        assertThat(JarvisRdvResolver.resolve(rdv("jeudi", "", "14:00", false), TUESDAY, PARIS)).isEmpty();
        // Sans heure, sans jour, date passée, date et jour contradictoires.
        assertThat(JarvisRdvResolver.resolve(rdv("jeudi", "", "", true), TUESDAY, PARIS)).isEmpty();
        assertThat(JarvisRdvResolver.resolve(rdv("", "", "14:00", true), TUESDAY, PARIS)).isEmpty();
        assertThat(JarvisRdvResolver.resolve(rdv("", "2026-10-01", "14:00", true), TUESDAY, PARIS)).isEmpty();
        assertThat(JarvisRdvResolver.resolve(rdv("vendredi", "2026-11-12", "14:00", true), TUESDAY, PARIS)).isEmpty();
    }

    @Test
    void theTriageRdvFieldIsReadBoundedAndNeverForIgnoredMail() {
        String out = """
                [{"id":"m1","classe":"A_TRAITER","raison":"Helios propose un point","rdv":{"titre":"Point <b>devis</b> \
                https://evil.example","jour_semaine":"Jeudi","date":"","heure":"14:00","duree_minutes":30,"lieu":"Visio",\
                "certitude":"haute"}},
                 {"id":"m2","classe":"A_IGNORER","raison":"Publicité","rdv":{"titre":"Webinaire","jour_semaine":"lundi",\
                "date":"","heure":"10:00","certitude":"haute"}},
                 {"id":"m3","classe":"A_LIRE","raison":"Compte rendu"}]""";
        var verdicts = JarvisMailTriageParser.parse(out, java.util.Set.of("m1", "m2", "m3"));
        MailRdv r = verdicts.get("m1").rdv();
        assertThat(r).isNotNull();
        assertThat(r.title()).isEqualTo("Point devis");
        assertThat(r.weekday()).isEqualTo("jeudi");
        assertThat(r.minutes()).isEqualTo(30);
        assertThat(r.certain()).isTrue();
        assertThat(verdicts.get("m2").triage()).isEqualTo(JarvisMailItem.Triage.A_IGNORER);
        assertThat(verdicts.get("m2").rdv()).isNull();
        assertThat(verdicts.get("m3").rdv()).isNull();
        assertThat(MailRdv.parse(new ObjectMapper().createObjectNode())).isNull();
    }

    @Test
    void anInvitationIsReadWithoutEverReadingItsAttendees() {
        String ics = ("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:test\nMETHOD:REQUEST\nBEGIN:VEVENT\n"
                + "DTSTART;TZID=Europe/Paris:20261105T140000\nDTEND;TZID=Europe/Paris:20261105T150000\n"
                + "UID:invit-helios@example\nSUMMARY:Point devis\nLOCATION:Visio\n"
                + "ORGANIZER:mailto:devis@helios.example\n"
                + "ATTENDEE;RSVP=TRUE:mailto:pirate@evil.example\nATTENDEE:mailto:pro@ng-itconsulting.com\n"
                + "END:VEVENT\nEND:VCALENDAR\n").replace("\n", "\r\n");
        JarvisIcsParser.Invitation inv = JarvisIcsParser.invitation(ics.getBytes(StandardCharsets.UTF_8), PARIS)
                .orElseThrow();
        assertThat(inv.method()).isEqualTo("REQUEST");
        assertThat(inv.uid()).isEqualTo("invit-helios@example");
        assertThat(inv.start()).isEqualTo(LocalDateTime.parse("2026-11-05T14:00").atZone(PARIS).toInstant());
        assertThat(inv.text().title()).isEqualTo("Point devis");
        assertThat(inv.toString()).doesNotContain("pirate");
        String cancel = ics.replace("METHOD:REQUEST", "METHOD:CANCEL");
        assertThat(JarvisIcsParser.invitation(cancel.getBytes(StandardCharsets.UTF_8), PARIS).orElseThrow().method())
                .isEqualTo("CANCEL");
        assertThat(JarvisIcsParser.invitation("pas un agenda".getBytes(StandardCharsets.UTF_8), PARIS)).isEmpty();
    }
}
