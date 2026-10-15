package fr.claudegateway.atelier.routine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/** F-183 / SF-183-01 — les créneaux d'une routine, dans le fuseau du poste. */
class RoutineSlotsTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final LocalTime EIGHT = LocalTime.of(8, 0);

    @Test
    void weekdaysFridayMorningAfterEightNextIsMonday() {
        // Vendredi 16 octobre 2026, 9 h à Paris.
        OffsetDateTime now = OffsetDateTime.parse("2026-10-16T09:00:00+02:00");
        assertThat(RoutineSlots.nextSlot(now, RoutineCadence.WEEKDAYS, null, EIGHT, PARIS))
                .contains(OffsetDateTime.parse("2026-10-19T08:00:00+02:00"));
        assertThat(RoutineSlots.dueSlot(now, RoutineCadence.WEEKDAYS, null, EIGHT, PARIS))
                .contains(OffsetDateTime.parse("2026-10-16T08:00:00+02:00"));
    }

    @Test
    void weekdaysOnSaturdayTheDueSlotIsFriday() {
        OffsetDateTime saturday = OffsetDateTime.parse("2026-10-17T08:30:00+02:00");
        assertThat(RoutineSlots.dueSlot(saturday, RoutineCadence.WEEKDAYS, null, EIGHT, PARIS))
                .contains(OffsetDateTime.parse("2026-10-16T08:00:00+02:00"));
    }

    @Test
    void dailyBeforeTheHourTheDueSlotIsYesterday() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-16T07:59:00+02:00");
        assertThat(RoutineSlots.dueSlot(now, RoutineCadence.DAILY, null, EIGHT, PARIS))
                .contains(OffsetDateTime.parse("2026-10-15T08:00:00+02:00"));
        assertThat(RoutineSlots.nextSlot(now, RoutineCadence.DAILY, null, EIGHT, PARIS))
                .contains(OffsetDateTime.parse("2026-10-16T08:00:00+02:00"));
    }

    @Test
    void exactlyOnTheHourTheSlotIsDue() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-16T08:00:00+02:00");
        assertThat(RoutineSlots.dueSlot(now, RoutineCadence.DAILY, null, EIGHT, PARIS)).contains(now);
    }

    @Test
    void weeklyFindsTheRightDay() {
        // Vendredi 17 h, hebdo le vendredi (5).
        OffsetDateTime thursday = OffsetDateTime.parse("2026-10-15T12:00:00+02:00");
        LocalTime five = LocalTime.of(17, 0);
        assertThat(RoutineSlots.nextSlot(thursday, RoutineCadence.WEEKLY, 5, five, PARIS))
                .contains(OffsetDateTime.parse("2026-10-16T17:00:00+02:00"));
        assertThat(RoutineSlots.dueSlot(thursday, RoutineCadence.WEEKLY, 5, five, PARIS))
                .contains(OffsetDateTime.parse("2026-10-09T17:00:00+02:00"));
    }

    @Test
    void weeklyWithoutDayHasNoSlot() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-15T12:00:00+02:00");
        assertThat(RoutineSlots.nextSlot(now, RoutineCadence.WEEKLY, null, EIGHT, PARIS)).isEmpty();
    }

    @Test
    void daylightSavingChangeKeepsLocalHour() {
        // Passage à l'heure d'hiver le dimanche 25 octobre 2026 : 8 h reste 8 h locale.
        OffsetDateTime saturday = OffsetDateTime.parse("2026-10-24T09:00:00+02:00");
        assertThat(RoutineSlots.nextSlot(saturday, RoutineCadence.DAILY, null, EIGHT, PARIS))
                .contains(OffsetDateTime.parse("2026-10-25T08:00:00+01:00"));
    }

    @Test
    void parsingRejectsGarbage() {
        assertThat(RoutineSlots.parseTime("8h")).isEmpty();
        assertThat(RoutineSlots.parseTime("24:00")).isEmpty();
        assertThat(RoutineSlots.parseTime("08:00")).contains(EIGHT);
        assertThat(RoutineSlots.parseZone("Mars/Olympus")).isEmpty();
        assertThat(RoutineSlots.parseZone("Europe/Paris")).contains(PARIS);
    }
}
