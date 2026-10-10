package fr.claudegateway.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import fr.claudegateway.push.PushEvent;

/** La règle des préférences (F-185 / SF-185-06), pure. */
class NotificationPreferencesTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    /** 2026-10-10 à l'heure de Paris (UTC+2). */
    private static Instant paris(String hour) {
        return Instant.parse("2026-10-10T" + hour + ":00+02:00");
    }

    @Test
    void byDefaultEverythingRings() {
        NotificationPreferences defaults = NotificationPreferences.defaults();
        for (PushEvent event : PushEvent.values()) {
            assertThat(defaults.allowsPush(event, paris("03:00"))).as(event.name()).isTrue();
        }
    }

    @Test
    void aMutedEventDoesNotRing() {
        NotificationPreferences prefs = new NotificationPreferences(Set.of(PushEvent.TURN_DONE), null, null, PARIS);
        assertThat(prefs.allowsPush(PushEvent.TURN_DONE, paris("12:00"))).isFalse();
        assertThat(prefs.allowsPush(PushEvent.PLAN_AWAITING, paris("12:00"))).isTrue();
    }

    @Test
    void quietHoursInTheUsersZoneIncludingAcrossMidnight() {
        NotificationPreferences day = new NotificationPreferences(Set.of(), LocalTime.of(12, 0), LocalTime.of(14, 0), PARIS);
        assertThat(day.allowsPush(PushEvent.TURN_DONE, paris("13:00"))).isFalse();
        assertThat(day.allowsPush(PushEvent.TURN_DONE, paris("14:00"))).isTrue();
        assertThat(day.allowsPush(PushEvent.TURN_DONE, paris("11:59"))).isTrue();

        NotificationPreferences night = new NotificationPreferences(Set.of(), LocalTime.of(22, 0), LocalTime.of(7, 0), PARIS);
        assertThat(night.allowsPush(PushEvent.TURN_DONE, paris("23:30"))).isFalse();
        assertThat(night.allowsPush(PushEvent.TURN_DONE, paris("06:59"))).isFalse();
        assertThat(night.allowsPush(PushEvent.TURN_DONE, paris("07:00"))).isTrue();
        assertThat(night.allowsPush(PushEvent.TURN_DONE, paris("15:00"))).isTrue();

        // Même instant, fuseau de New York (UTC-4) : 13:00 Paris = 07:00 New York, hors 22h-7h.
        NotificationPreferences ny = new NotificationPreferences(Set.of(), LocalTime.of(22, 0), LocalTime.of(7, 0),
                ZoneId.of("America/New_York"));
        assertThat(ny.allowsPush(PushEvent.TURN_DONE, paris("13:00"))).isTrue();
        assertThat(ny.allowsPush(PushEvent.TURN_DONE, paris("12:59"))).isFalse();
    }

    @Test
    void criticalEventsAlwaysRing() {
        NotificationPreferences prefs = new NotificationPreferences(Set.of(), LocalTime.of(0, 0), LocalTime.of(23, 59), PARIS);
        assertThat(prefs.allowsPush(PushEvent.AUTHORIZATION_REQUESTED, paris("12:00"))).isTrue();
        assertThat(prefs.allowsPush(PushEvent.QUESTION_ASKED, paris("12:00"))).isTrue();
        assertThat(prefs.allowsPush(PushEvent.QUESTION_REMINDER, paris("12:00"))).isTrue();
        assertThat(prefs.allowsPush(PushEvent.TURN_DONE, paris("12:00"))).isFalse();
    }

    @Test
    void parseRejectsWhatTheScreenMustNotSend() {
        assertThatThrownBy(() -> NotificationPreferences.parse(List.of("NOPE"), null, null, "Europe/Paris"))
                .hasMessageContaining("Événement inconnu");
        assertThatThrownBy(() -> NotificationPreferences.parse(List.of("QUESTION_ASKED"), null, null, "Europe/Paris"))
                .hasMessageContaining("ne peut pas être coupé");
        assertThatThrownBy(() -> NotificationPreferences.parse(List.of(), "25:00", "07:00", "Europe/Paris"))
                .hasMessageContaining("Heure invalide");
        assertThatThrownBy(() -> NotificationPreferences.parse(List.of(), "7h", "08:00", "Europe/Paris"))
                .hasMessageContaining("Heure invalide");
        assertThatThrownBy(() -> NotificationPreferences.parse(List.of(), "22:00", null, "Europe/Paris"))
                .hasMessageContaining("début et une fin");
        assertThatThrownBy(() -> NotificationPreferences.parse(List.of(), "08:00", "08:00", "Europe/Paris"))
                .hasMessageContaining("différentes");
        assertThatThrownBy(() -> NotificationPreferences.parse(List.of(), null, null, "Mars/Olympus"))
                .hasMessageContaining("Fuseau horaire inconnu");
    }

    @Test
    void parseAcceptsAValidRequest() {
        NotificationPreferences prefs = NotificationPreferences.parse(List.of("TURN_DONE", "WORK_STOPPED"),
                "22:00", "07:00", "Europe/Paris");
        assertThat(prefs.muted()).containsExactlyInAnyOrder(PushEvent.TURN_DONE, PushEvent.WORK_STOPPED);
        assertThat(prefs.quietFrom()).isEqualTo(LocalTime.of(22, 0));
        assertThat(prefs.zone()).isEqualTo(PARIS);
    }

    @Test
    void aStoredRowIsReadTolerantly() {
        NotificationPreference row = NotificationPreference.builder()
                .mutedEvents("TURN_DONE, OLD_EVENT,QUESTION_ASKED").timeZone("Nowhere/Zone").build();
        NotificationPreferences prefs = NotificationPreferences.fromRow(row);
        // Code inconnu ignoré, critique jamais coupé même s'il est en base, fuseau illisible → défaut.
        assertThat(prefs.muted()).containsExactly(PushEvent.TURN_DONE);
        assertThat(prefs.zone()).isEqualTo(PARIS);
    }
}
