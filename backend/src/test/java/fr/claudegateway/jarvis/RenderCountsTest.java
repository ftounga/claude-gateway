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

    @Test
    @DisplayName("F-237 / SF-237-14 — les compteurs des pièces sont au vocabulaire, le même que l'écran")
    void pieceKeys() throws Exception {
        String front = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../frontend/src/app/jarvis/pieces/jarvis-piece-measure.ts"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([a-z_0-9]+)'")
                .matcher(front.substring(front.indexOf("PIECE_COUNT_KEYS"), front.indexOf("] as const")));
        java.util.List<String> keys = new java.util.ArrayList<>();
        while (m.find()) {
            keys.add(m.group(1));
        }
        assertThat(keys).hasSizeGreaterThan(30);
        assertThat(RenderCounts.KEYS).containsAll(keys);
        assertThat(RenderCounts.validate(Map.of("p_gal", 2, "v_sec", 120, "lat_1_2", 1))).hasSize(3);
    }

    @Test
    @DisplayName("F-248 / SF-248-01 — les 16 compteurs des pancartes sont au vocabulaire ; la colonne passe à 2 000")
    void cardKeys() {
        String[] keys = {"c_set", "c_n1", "c_n2_6", "c_n7p", "c_tgt", "c_cit", "c_open", "c_link", "c_big", "c_cmp",
                "c_more", "c_voice", "c_touch", "c_close2s", "c_old", "c_2d"};
        assertThat(RenderCounts.KEYS).contains(keys);
        assertThat(RenderCounts.validate(Map.of("c_set", 3, "c_open", 1))).hasSize(2);
        assertThat(RenderCounts.parse("c_set=2;c_inconnu=4")).containsExactly(Map.entry("c_set", 2));
        assertThat(RenderCounts.MAX_LENGTH).isEqualTo(2_000);
    }

    @Test
    @DisplayName("F-237 / SF-237-14 — jamais plus de 1 000 caractères : les plus petits compteurs sont laissés")
    void neverLongerThanTheColumn() {
        Map<String, Integer> all = new HashMap<>();
        for (String key : RenderCounts.KEYS) {
            all.put(key, 86_400);
        }
        all.put("p_gal", 1);
        String out = RenderCounts.format(all);
        assertThat(out.length()).isLessThanOrEqualTo(RenderCounts.MAX_LENGTH);
    }
}
