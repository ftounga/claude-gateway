package fr.claudegateway.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** Les jours fériés France, calculés (F-124 / SF-124-02). */
class FrenchHolidaysTest {

    @Test
    void computesEasterSunday() {
        // Références connues du calendrier grégorien.
        assertThat(FrenchHolidays.easterSunday(2025)).isEqualTo(LocalDate.of(2025, 4, 20));
        assertThat(FrenchHolidays.easterSunday(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(FrenchHolidays.easterSunday(2024)).isEqualTo(LocalDate.of(2024, 3, 31));
    }

    @Test
    void includesFixedHolidays() {
        var holidays = FrenchHolidays.of(2025);
        assertThat(holidays).contains(
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 8),
                LocalDate.of(2025, 7, 14), LocalDate.of(2025, 8, 15), LocalDate.of(2025, 11, 1),
                LocalDate.of(2025, 11, 11), LocalDate.of(2025, 12, 25));
    }

    @Test
    void includesEasterBasedHolidays() {
        var holidays = FrenchHolidays.of(2025);
        assertThat(holidays).contains(
                LocalDate.of(2025, 4, 21),  // Lundi de Pâques (Pâques + 1)
                LocalDate.of(2025, 5, 29),  // Ascension (Pâques + 39)
                LocalDate.of(2025, 6, 9));  // Lundi de Pentecôte (Pâques + 50)
    }

    @Test
    void isHoliday_recognizesAndRejects() {
        assertThat(FrenchHolidays.isHoliday(LocalDate.of(2025, 5, 1))).isTrue();
        assertThat(FrenchHolidays.isHoliday(LocalDate.of(2025, 5, 29))).isTrue();
        assertThat(FrenchHolidays.isHoliday(LocalDate.of(2025, 5, 2))).isFalse();
    }
}
