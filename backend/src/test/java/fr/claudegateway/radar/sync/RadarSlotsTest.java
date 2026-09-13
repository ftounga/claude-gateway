package fr.claudegateway.radar.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-100 / SF-100-02 — les créneaux du soir, dans le fuseau du poste. */
class RadarSlotsTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime TEN_PM = LocalTime.of(22, 0);

    @Test
    @DisplayName("Avant 22 h, le créneau dû est celui de la veille ; à 22 h et après, celui du jour")
    void dueSlot() {
        assertThat(RadarSlots.dueSlotDate(OffsetDateTime.parse("2026-09-13T19:59:00Z"), TEN_PM, PARIS))
                .isEqualTo(LocalDate.parse("2026-09-12")); // 21:59 à Paris
        assertThat(RadarSlots.dueSlotDate(OffsetDateTime.parse("2026-09-13T20:00:00Z"), TEN_PM, PARIS))
                .isEqualTo(LocalDate.parse("2026-09-13")); // 22:00 à Paris
    }

    @Test
    @DisplayName("22:00 à Paris et 22:00 à New York ne sont pas le même instant")
    void zonesDiffer() {
        LocalDate day = LocalDate.parse("2026-09-13");
        assertThat(RadarSlots.slotInstant(day, TEN_PM, PARIS).toInstant().toString()).isEqualTo("2026-09-13T20:00:00Z");
        assertThat(RadarSlots.slotInstant(day, TEN_PM, NEW_YORK).toInstant().toString()).isEqualTo("2026-09-14T02:00:00Z");
    }

    @Test
    @DisplayName("Changement d'heure : le créneau reste à l'heure locale ; une heure inexistante glisse")
    void daylightSaving() {
        assertThat(RadarSlots.slotInstant(LocalDate.parse("2026-10-24"), TEN_PM, PARIS).toInstant().toString())
                .isEqualTo("2026-10-24T20:00:00Z");
        assertThat(RadarSlots.slotInstant(LocalDate.parse("2026-10-25"), TEN_PM, PARIS).toInstant().toString())
                .isEqualTo("2026-10-25T21:00:00Z");
        assertThat(RadarSlots.slotInstant(LocalDate.parse("2026-03-29"), LocalTime.of(2, 30), PARIS).toLocalTime())
                .isEqualTo(LocalTime.of(3, 30));
    }

    @Test
    @DisplayName("Prochain créneau ; heure et fuseau validés")
    void nextAndParsing() {
        assertThat(RadarSlots.nextSlot(OffsetDateTime.parse("2026-09-13T21:00:00Z"), TEN_PM, PARIS).toInstant().toString())
                .isEqualTo("2026-09-14T20:00:00Z");
        assertThat(RadarSlots.parseTime("22:00")).contains(TEN_PM);
        assertThat(RadarSlots.parseTime("24:00")).isEmpty();
        assertThat(RadarSlots.parseTime("7:00")).isEmpty();
        assertThat(RadarSlots.parseZone("Europe/Paris")).contains(PARIS);
        assertThat(RadarSlots.parseZone("Mars/Olympus")).isEmpty();
        assertThat(RadarSlots.parseZone("")).isEmpty();
    }
}
