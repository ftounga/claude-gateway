package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

/** F-101 / SF-101-04 — la relance due : seulement ce qu'on attend des autres, en jours ouvrés. */
class RadarFollowUpTest {

    private static final OffsetDateTime THURSDAY = OffsetDateTime.parse("2026-09-10T16:00:00Z");

    @Test
    void withoutDueDateThreeBusinessDaysAfterTheLastEvidence() {
        assertThat(RadarFollowUp.dueOn(RadarCommitmentDirection.OTHER_TO_ME, RadarCommitmentStatus.OPEN, false,
                null, THURSDAY)).isEqualTo(LocalDate.of(2026, 9, 15)); // jeudi → mardi
    }

    @Test
    void withDueDateTheFirstBusinessDayAfter() {
        assertThat(RadarFollowUp.dueOn(RadarCommitmentDirection.OTHER_TO_ME, RadarCommitmentStatus.OPEN, false,
                LocalDate.of(2026, 9, 11), THURSDAY)).isEqualTo(LocalDate.of(2026, 9, 14)); // vendredi → lundi
    }

    @Test
    void nothingToChaseOtherwise() {
        assertThat(RadarFollowUp.dueOn(RadarCommitmentDirection.ME_TO_OTHER, RadarCommitmentStatus.OPEN, false, null,
                THURSDAY)).isNull();
        assertThat(RadarFollowUp.dueOn(RadarCommitmentDirection.INTRODUCTION, RadarCommitmentStatus.OPEN, false, null,
                THURSDAY)).isNull();
        assertThat(RadarFollowUp.dueOn(RadarCommitmentDirection.OTHER_TO_ME, RadarCommitmentStatus.KEPT, false, null,
                THURSDAY)).isNull();
        assertThat(RadarFollowUp.dueOn(RadarCommitmentDirection.OTHER_TO_ME, RadarCommitmentStatus.OPEN, true, null,
                THURSDAY)).isNull();
        assertThat(RadarFollowUp.dueOn(RadarCommitmentDirection.OTHER_TO_ME, RadarCommitmentStatus.OPEN, false, null,
                null)).isNull();
    }

    @Test
    void businessDaysSkipWeekends() {
        assertThat(RadarFollowUp.addBusinessDays(LocalDate.of(2026, 9, 12), 1)).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(RadarFollowUp.addBusinessDays(LocalDate.of(2026, 9, 14), 5)).isEqualTo(LocalDate.of(2026, 9, 21));
    }
}
