package fr.claudegateway.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

/** Le compte des jours ouvrés (lun–ven hors fériés France) — F-124 / SF-124-02. */
class WorkdayCalendarTest {

    @Test
    void countsPlainMonthWithoutHolidays() {
        // Février 2025 : 28 jours, 4 semaines pleines, aucun férié → 20 jours ouvrés.
        assertThat(WorkdayCalendar.businessDaysInMonth(YearMonth.of(2025, 2))).isEqualTo(20);
    }

    @Test
    void excludesWeekends() {
        // Septembre 2025 : 30 jours, 8 jours de week-end, aucun férié → 22 jours ouvrés.
        assertThat(WorkdayCalendar.businessDaysInMonth(YearMonth.of(2025, 9))).isEqualTo(22);
    }

    @Test
    void excludesFrenchHolidaysThatFallOnWeekdays() {
        // Mai 2025 : 22 jours de semaine, dont 3 fériés en semaine (1er mai jeu, 8 mai jeu,
        // Ascension 29 mai jeu) → 19 jours ouvrés.
        assertThat(WorkdayCalendar.businessDaysInMonth(YearMonth.of(2025, 5))).isEqualTo(19);
    }

    // ---------------------------------------------------------------- businessDaysBetween (SF-124-04)

    @Test
    void countsBusinessDaysInAnInclusiveInterval() {
        // Du 10 au 31 août 2025, hors 15/08 (Assomption, vendredi) → 14 jours ouvrés.
        assertThat(WorkdayCalendar.businessDaysBetween(
                LocalDate.of(2025, 8, 10), LocalDate.of(2025, 8, 31))).isEqualTo(14);
    }

    @Test
    void countsAShortInterval() {
        // Du 10 au 20 août 2025 : 11, 12, 13, 14, 18, 19, 20 (15 férié, week-ends exclus) → 7.
        assertThat(WorkdayCalendar.businessDaysBetween(
                LocalDate.of(2025, 8, 10), LocalDate.of(2025, 8, 20))).isEqualTo(7);
    }

    @Test
    void singleBusinessDayCountsAsOne_andWeekendAsZero() {
        assertThat(WorkdayCalendar.businessDaysBetween(
                LocalDate.of(2025, 8, 11), LocalDate.of(2025, 8, 11))).isEqualTo(1); // lundi
        assertThat(WorkdayCalendar.businessDaysBetween(
                LocalDate.of(2025, 8, 16), LocalDate.of(2025, 8, 17))).isZero();     // sam+dim
    }

    @Test
    void emptyIntervalWhenFromIsAfterTo() {
        assertThat(WorkdayCalendar.businessDaysBetween(
                LocalDate.of(2025, 8, 20), LocalDate.of(2025, 8, 10))).isZero();
    }
}
