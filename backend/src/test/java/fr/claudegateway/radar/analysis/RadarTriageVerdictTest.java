package fr.claudegateway.radar.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** F-101 / SF-101-02 — la forme du tri : seul le dernier bloc compte, et un libellé inventé rend tout illisible. */
class RadarTriageVerdictTest {

    @Test
    void nominal() {
        RadarTriageVerdict v = RadarTriageVerdict.parse("Je pèse.\n===TRI===\n{\"retenus\": [\"E2\"]}", 1, 3);
        assertThat(v.lisible()).isTrue();
        assertThat(v.retained()).containsExactly(1);
    }

    @Test
    void lastMarkerWinsAndFencesAreTolerated() {
        String response = "La forme est ===TRI=== {\"retenus\": [\"E1\"]} ; voici ma réponse.\n"
                + "===TRI===\n```json\n{\"retenus\": [\"E3\", \"E3\", \"E1\"]}\n```";
        RadarTriageVerdict v = RadarTriageVerdict.parse(response, 1, 3);
        assertThat(v.lisible()).isTrue();
        assertThat(v.retained()).containsExactlyInAnyOrder(0, 2);
    }

    @Test
    void emptyListIsReadable() {
        RadarTriageVerdict v = RadarTriageVerdict.parse("===TRI===\n{\"retenus\": []}", 1, 2);
        assertThat(v.lisible()).isTrue();
        assertThat(v.retained()).isEmpty();
    }

    @Test
    void offsetChunk() {
        RadarTriageVerdict v = RadarTriageVerdict.parse("===TRI===\n{\"retenus\": [\"E5\"]}", 4, 2);
        assertThat(v.retained()).containsExactly(4);
        assertThat(RadarTriageVerdict.parse("===TRI===\n{\"retenus\": [\"E3\"]}", 4, 2).lisible()).isFalse();
    }

    @Test
    void unreadable() {
        assertThat(RadarTriageVerdict.parse(null, 1, 1).lisible()).isFalse();
        assertThat(RadarTriageVerdict.parse("Rien à retenir.", 1, 1).lisible()).isFalse();
        assertThat(RadarTriageVerdict.parse("===TRI===\n", 1, 1).lisible()).isFalse();
        assertThat(RadarTriageVerdict.parse("===TRI===\n{\"retenus\": [\"E1\"", 1, 1).lisible()).isFalse();
        assertThat(RadarTriageVerdict.parse("===TRI===\n{\"retenus\": \"E1\"}", 1, 1).lisible()).isFalse();
        assertThat(RadarTriageVerdict.parse("===TRI===\n{\"garder\": [\"E1\"]}", 1, 1).lisible()).isFalse();
        assertThat(RadarTriageVerdict.parse("===TRI===\n{\"retenus\": [\"E0\"]}", 1, 1).lisible()).isFalse();
        assertThat(RadarTriageVerdict.parse("===TRI===\n{\"retenus\": [\"E9\"]}", 1, 3).lisible()).isFalse();
        assertThat(RadarTriageVerdict.parse("===TRI===\n{\"retenus\": [\"X1\"]}", 1, 3).lisible()).isFalse();
        assertThat(RadarTriageVerdict.parse("===TRI===\n{\"retenus\": [1]}", 1, 3).lisible()).isFalse();
        assertThat(RadarTriageVerdict.parse("===TRI===\n{\"retenus\": []} et puis", 1, 3).lisible()).isFalse();
    }
}
