package fr.claudegateway.jarvis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** F-234 / SF-234-11 (D16) — le détail des scènes : identifiants et nombres seulement. */
class SceneCountsTest {

    @Test
    void litEtEcritTrieEnIgnorantLIllisible() {
        assertThat(SceneCounts.parse("S5=4;S3=12;x;=2;bad=z;GENERIQUE=2"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("S3", 12, "S5", 4, "GENERIQUE", 2));
        assertThat(SceneCounts.format(SceneCounts.parse("S5=4;S3=12"))).isEqualTo("S3=12;S5=4");
        assertThat(SceneCounts.parse(null)).isEmpty();
    }

    @Test
    void refuseCeQuiNEstPasUnIdentifiantDeScene() {
        assertThat(SceneCounts.validate(Map.of("S5b", 2))).containsEntry("S5b", 2);
        assertThatThrownBy(() -> SceneCounts.validate(Map.of("zahi", 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SceneCounts.validate(Map.of("S3", -1))).isInstanceOf(IllegalArgumentException.class);
    }
}
