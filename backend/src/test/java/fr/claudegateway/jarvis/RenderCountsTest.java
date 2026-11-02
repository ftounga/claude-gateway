package fr.claudegateway.jarvis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-236 / SF-236-06 — le détail du rendu : vocabulaire fermé, bornes, lecture tolérante, écriture triée. */
class RenderCountsTest {

    @Test
    @DisplayName("lecture / écriture : aller-retour trié ; clés inconnues et entrées illisibles ignorées")
    void parseFormat() {
        Map<String, Integer> counts = RenderCounts.parse("sec_high=10;r_manual=2;Zahi=3;fps_50_60=x;;t_core=1");
        assertThat(counts).containsExactly(Map.entry("r_manual", 2), Map.entry("sec_high", 10), Map.entry("t_core", 1));
        assertThat(RenderCounts.format(counts)).isEqualTo("r_manual=2;sec_high=10;t_core=1");
        assertThat(RenderCounts.parse(null)).isEmpty();
        assertThat(RenderCounts.parse(" ")).isEmpty();
    }

    @Test
    @DisplayName("validation : clé hors vocabulaire, nombre négatif ou au-delà de 86 400 ⇒ refus ; vide ⇒ rien")
    void validate() {
        assertThat(RenderCounts.validate(null)).isEmpty();
        assertThat(RenderCounts.validate(Map.of("gestures", 3, "sec_low", 86_400))).hasSize(2);
        assertThatThrownBy(() -> RenderCounts.validate(Map.of("Migration Forge", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RenderCounts.validate(Map.of("gestures", -1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RenderCounts.validate(Map.of("gestures", 86_401)))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Integer> nulls = new HashMap<>();
        nulls.put("gestures", null);
        assertThatThrownBy(() -> RenderCounts.validate(nulls)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("le vocabulaire couvre les raisons du repli 2D de l'écran (T10 / T11 / qualité)")
    void reasons() {
        for (String reason : new String[] {"no_webgl", "perf_caveat", "reduced_motion", "save_data", "sober", "manual",
                "context_lost", "low_perf"}) {
            assertThat(RenderCounts.KEYS).contains("r_" + reason);
        }
    }
}
