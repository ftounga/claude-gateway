package fr.claudegateway.atelier.gallery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-278 / SF-278-01 — la date lue dans le nom d'une capture. */
class GalleryDatesTest {

    @Test
    @DisplayName("les noms usuels des captures donnent leur date")
    void usualNames() {
        assertThat(GalleryDates.fromName("Capture d'écran 2026-11-27 093012.png"))
                .contains(LocalDateTime.of(2026, 11, 27, 9, 30, 12));
        assertThat(GalleryDates.fromName("Screenshot_20261127_093012.png"))
                .contains(LocalDateTime.of(2026, 11, 27, 9, 30, 12));
        assertThat(GalleryDates.fromName("Capture d’écran 2026-11-27 à 09.30.12.png"))
                .contains(LocalDateTime.of(2026, 11, 27, 9, 30, 12));
        assertThat(GalleryDates.fromName("IMG-20261127-WA0003.jpg"))
                .contains(LocalDateTime.of(2026, 11, 27, 0, 0));
    }

    @Test
    @DisplayName("sans date, ou une date impossible, rien")
    void noDate() {
        assertThat(GalleryDates.fromName("schema-reseau.png")).isEmpty();
        assertThat(GalleryDates.fromName("2026-13-45.png")).isEmpty();
        assertThat(GalleryDates.fromName(null)).isEmpty();
        assertThat(GalleryDates.fromName("ticket-123456789.png")).isEmpty();
    }
}
