package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * La fenêtre de dépense (F-133 / SF-133-03) : semaine ISO du lundi, mois calendaire, bornes UTC.
 */
class CostWindowTest {

    @Test
    void everyDayOfTheSameWeekYieldsTheSameMonday() {
        // Le 2026-09-20 est un DIMANCHE : en semaine ISO il appartient à la semaine du lundi 14,
        // pas à celle du 21. C'est l'erreur classique, et c'est pour ça que ce test existe.
        CostWindow fromSunday = CostWindow.week(LocalDate.of(2026, 9, 20));
        CostWindow fromMonday = CostWindow.week(LocalDate.of(2026, 9, 14));
        CostWindow fromWednesday = CostWindow.week(LocalDate.of(2026, 9, 16));

        assertThat(fromSunday.firstDay()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(fromSunday.lastDay()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(fromMonday).isEqualTo(fromSunday);
        assertThat(fromWednesday).isEqualTo(fromSunday);
    }

    @Test
    void aWeekCanStraddleTwoMonths() {
        // Du lundi 28 septembre au dimanche 4 octobre : une dépense hebdomadaire ne s'arrête pas à
        // la fin du mois, et un budget de semaine non plus.
        CostWindow week = CostWindow.week(LocalDate.of(2026, 10, 1));

        assertThat(week.firstDay()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(week.lastDay()).isEqualTo(LocalDate.of(2026, 10, 4));
    }

    @Test
    void aWeekCanStraddleTwoYears() {
        CostWindow week = CostWindow.week(LocalDate.of(2027, 1, 1));

        assertThat(week.firstDay()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(week.lastDay()).isEqualTo(LocalDate.of(2027, 1, 3));
    }

    @Test
    void theMonthIsTheCalendarMonth() {
        CostWindow month = CostWindow.month(LocalDate.of(2026, 9, 20));

        assertThat(month.firstDay()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(month.lastDay()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void boundsAreUtcMidnightAndTheEndIsExclusive() {
        CostWindow week = CostWindow.week(LocalDate.of(2026, 9, 16));

        assertThat(week.start().toString()).isEqualTo("2026-09-14T00:00Z");
        assertThat(week.end().toString()).isEqualTo("2026-09-21T00:00Z");
    }

    @Test
    void refusesAnInvertedWindow() {
        assertThatThrownBy(() -> CostWindow.between(
                LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 10)))
                .isInstanceOf(InvalidUsageWindowException.class)
                .hasMessageContaining("précéder");
    }

    @Test
    void refusesAWindowBeyondTheCap() {
        assertThatThrownBy(() -> CostWindow.between(
                LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(InvalidUsageWindowException.class)
                .hasMessageContaining("dépasse");
    }
}
