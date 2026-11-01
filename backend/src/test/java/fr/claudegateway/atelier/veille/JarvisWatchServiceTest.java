package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/** F-230 / SF-230-01 — les règles pures de la pose : heures, script, créneaux lundi→vendredi. */
class JarvisWatchServiceTest {

    @Test
    void hoursAreSortedDistinctAndBounded() {
        assertThat(JarvisWatchService.runTimes(List.of("14:00", "9:00", "09:00"))).isEqualTo("09:00,14:00");
        assertThatThrownBy(() -> JarvisWatchService.runTimes(List.of()))
                .isInstanceOf(JarvisWatchExceptions.InvalidWatchException.class);
        assertThatThrownBy(() -> JarvisWatchService.runTimes(List.of("25:00")))
                .isInstanceOf(JarvisWatchExceptions.InvalidWatchException.class);
        assertThatThrownBy(() -> JarvisWatchService.runTimes(
                List.of("08:00", "09:00", "10:00", "11:00", "12:00", "13:00", "14:00")))
                .hasMessageContaining("6 fois");
    }

    @Test
    void theScriptMustBeDeclaredRelativeAndCalledByTheCommand() {
        assertThat(JarvisWatchService.scriptPath(null, "curl -s https://x | jq .")).isNull();
        assertThat(JarvisWatchService.scriptPath("./.atelier/veilles/a.sh", "bash .atelier/veilles/a.sh"))
                .isEqualTo(".atelier/veilles/a.sh");
        assertThatThrownBy(() -> JarvisWatchService.scriptPath(null, "bash a.sh"))
                .isInstanceOf(JarvisWatchExceptions.UnsafeProbeException.class);
        assertThatThrownBy(() -> JarvisWatchService.scriptPath("../a.sh", "bash ../a.sh"))
                .isInstanceOf(JarvisWatchExceptions.InvalidWatchException.class);
        assertThatThrownBy(() -> JarvisWatchService.scriptPath("/etc/a.sh", "bash /etc/a.sh"))
                .isInstanceOf(JarvisWatchExceptions.InvalidWatchException.class);
        assertThatThrownBy(() -> JarvisWatchService.scriptPath("b.sh", "bash a.sh"))
                .isInstanceOf(JarvisWatchExceptions.UnsafeProbeException.class);
    }

    @Test
    void slotsAreMondayToFridayAcrossSeveralHours() {
        JarvisWatch watch = JarvisWatch.builder().runTimes("09:00,14:00").timeZone("Europe/Paris").active(true)
                .proposed(false).build();
        // Vendredi 31/10/2026 à 15 h (Paris, UTC+1) : dû = vendredi 14 h ; prochain = lundi 9 h.
        OffsetDateTime friday = OffsetDateTime.parse("2026-10-30T15:00:00+01:00");
        assertThat(JarvisWatchSlots.dueSlot(watch, friday)).contains(OffsetDateTime.parse("2026-10-30T14:00:00+01:00"));
        assertThat(JarvisWatchSlots.nextSlot(watch, friday)).contains(OffsetDateTime.parse("2026-11-02T09:00:00+01:00"));
        // Samedi : le dernier créneau dû reste celui du vendredi 14 h — aucun passage le week-end.
        OffsetDateTime saturday = OffsetDateTime.parse("2026-10-31T10:00:00+01:00");
        assertThat(JarvisWatchSlots.dueSlot(watch, saturday)).contains(OffsetDateTime.parse("2026-10-30T14:00:00+01:00"));
    }
}
