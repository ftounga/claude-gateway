package fr.claudegateway.jarvis.timing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les percentiles du chronomètre (F-233 / SF-233-01) : rang le plus proche, vides ignorés. */
class JarvisLatencyServiceTest {

    @Test
    @DisplayName("percentile au rang le plus proche ; null sans valeur")
    void percentile() {
        assertThat(JarvisLatencyService.percentile(List.of(), 50)).isNull();
        assertThat(JarvisLatencyService.percentile(List.of(7), 90)).isEqualTo(7);
        List<Integer> ten = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(JarvisLatencyService.percentile(ten, 50)).isEqualTo(5);
        assertThat(JarvisLatencyService.percentile(ten, 90)).isEqualTo(9);
    }

    @Test
    @DisplayName("une étape ignore les tours où elle n'a pas eu lieu et trie les valeurs")
    void stepIgnoresNulls() {
        JarvisLatencyService.Step step = JarvisLatencyService.step("firstSound", Arrays.asList(900, null, 300, 600));
        assertThat(step.count()).isEqualTo(3);
        assertThat(step.p50()).isEqualTo(600);
        assertThat(step.p90()).isEqualTo(900);
    }

    @Test
    @DisplayName("les étapes suivent l'ordre du tour")
    void stepsOrder() {
        assertThat(JarvisLatencyService.STEPS.keySet()).containsExactly("speech", "transcript", "reaction", "sent",
                "firstToken", "firstOralSentence", "firstAudioByte", "firstSound", "done");
    }
}
