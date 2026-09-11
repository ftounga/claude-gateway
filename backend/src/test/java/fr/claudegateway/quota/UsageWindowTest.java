package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * Tests de la fenêtre d'observation (F-61) : normalisation au mois, bornes par défaut, et les deux
 * refus — fenêtre inversée, fenêtre trop longue.
 */
class UsageWindowTest {

    // 2026-09-11 → mois courant : 2026-09-01.
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void defaultsToTwelveMonthsEndingThisMonth() {
        UsageWindow window = UsageWindow.of(null, null, clock);

        assertThat(window.from()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(window.to()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void anyDayOfTheMonthIsNormalisedToItsFirst() {
        // Les compteurs ont le mois pour grain : répondre « du 3 au 17 » par les chiffres du mois
        // entier serait moins grave que de laisser croire à une précision au jour.
        UsageWindow window = UsageWindow.of(LocalDate.of(2026, 4, 17), LocalDate.of(2026, 6, 29), clock);

        assertThat(window.from()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(window.to()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void endMonthIsIncludedInTheWindow() {
        UsageWindow window = UsageWindow.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), clock);

        assertThat(window.exclusiveEnd()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(window.contains(LocalDate.of(2026, 9, 1))).isTrue();
        assertThat(window.contains(LocalDate.of(2026, 10, 1))).isFalse();
    }

    @Test
    void invertedWindowIsRefused() {
        assertThatThrownBy(() -> UsageWindow.of(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 4, 1), clock))
                .isInstanceOf(InvalidUsageWindowException.class)
                .hasMessageContaining("précéder");
    }

    @Test
    void windowLongerThanTheHardCapIsRefused() {
        assertThatThrownBy(() -> UsageWindow.of(
                LocalDate.of(2024, 1, 1), LocalDate.of(2026, 9, 1), clock))
                .isInstanceOf(InvalidUsageWindowException.class)
                .hasMessageContaining("24 mois");
    }

    @Test
    void windowOfExactlyTheHardCapIsAccepted() {
        UsageWindow window = UsageWindow.of(
                LocalDate.of(2024, 10, 1), LocalDate.of(2026, 9, 1), clock);

        assertThat(window.from()).isEqualTo(LocalDate.of(2024, 10, 1));
    }

    @Test
    void instantsAreUtcBounds() {
        UsageWindow window = UsageWindow.of(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), clock);

        assertThat(window.startInstant().toString()).startsWith("2026-08-01T00:00Z");
        assertThat(window.endInstant().toString()).startsWith("2026-09-01T00:00Z");
    }
}
