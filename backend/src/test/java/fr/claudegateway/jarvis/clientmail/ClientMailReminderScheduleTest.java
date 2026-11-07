package fr.claudegateway.jarvis.clientmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-249 / SF-249-05 — les échéances du rappel du soir. */
class ClientMailReminderScheduleTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final LocalTime FIRST = LocalTime.of(18, 30);

    private static int due(String at) {
        return ClientMailReminder.due(ZonedDateTime.of(java.time.LocalDateTime.parse(at), PARIS), FIRST, 3, 45, true);
    }

    @Test
    @DisplayName("Avant, à l'heure, entre deux, après le dernier ; jamais plus que le maximum")
    void schedule() {
        assertThat(due("2026-11-06T18:29")).isZero();
        assertThat(due("2026-11-06T18:30")).isEqualTo(1);
        assertThat(due("2026-11-06T19:14")).isEqualTo(1);
        assertThat(due("2026-11-06T19:15")).isEqualTo(2);
        assertThat(due("2026-11-06T20:00")).isEqualTo(3);
        assertThat(due("2026-11-06T23:59")).isEqualTo(3);
    }

    @Test
    @DisplayName("Jours ouvrés : rien le samedi ; sinon le samedi compte")
    void weekdays() {
        assertThat(due("2026-11-07T19:00")).isZero();
        assertThat(ClientMailReminder.due(ZonedDateTime.of(java.time.LocalDateTime.parse("2026-11-07T19:00"), PARIS),
                FIRST, 3, 45, false)).isEqualTo(1);
    }
}
