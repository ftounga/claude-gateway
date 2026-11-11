package fr.claudegateway.jarvis.timing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les percentiles du chronomètre (F-233 / SF-233-01) : rang le plus proche, vides ignorés. */
class JarvisLatencyServiceTest {

    @Test
    void emptyDiagnosticKeepsOnlyItsClosedVocabulary() {
        org.assertj.core.api.Assertions.assertThat(JarvisLatencyService.diagnostic("stop=max_tokens outils=aucun rejets=ancrage:1"))
                .isEqualTo("stop=max_tokens outils=aucun rejets=ancrage:1");
        org.assertj.core.api.Assertions.assertThat(JarvisLatencyService.diagnostic("stop=« du contenu »")).isNull();
        org.assertj.core.api.Assertions.assertThat(JarvisLatencyService.diagnostic("a".repeat(201))).isNull();
        org.assertj.core.api.Assertions.assertThat(JarvisLatencyService.diagnostic(null)).isNull();
    }

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

    @Test
    @DisplayName("F-257 / SF-257-01 — byLoop : legacy puis stream, NULL compté legacy, replis et adoption")
    void byLoopComparesBothLoops() {
        List<JarvisTurnTiming> rows = List.of(
                JarvisTurnTiming.builder().voiceLoop(null).firstSoundMs(5000).streamFallback("ouverture_fermee")
                        .speculation("perdue").build(),
                JarvisTurnTiming.builder().voiceLoop("legacy").firstSoundMs(6000).speculation("adoptee").build(),
                JarvisTurnTiming.builder().voiceLoop("stream").firstSoundMs(1800).build());
        List<JarvisLatencyService.LoopMeasure> loops = JarvisLatencyService.byLoop(rows);
        assertThat(loops).extracting(JarvisLatencyService.LoopMeasure::loop).containsExactly("legacy", "stream");
        JarvisLatencyService.LoopMeasure legacy = loops.get(0);
        assertThat(legacy.turns()).isEqualTo(2);
        assertThat(legacy.streamFallbacks()).isEqualTo(1);
        assertThat(legacy.adoptionRate()).isEqualTo(0.5);
        assertThat(legacy.steps()).filteredOn(s -> s.step().equals("firstSound")).singleElement()
                .satisfies(s -> assertThat(s.p50()).isEqualTo(5000));
        JarvisLatencyService.LoopMeasure stream = loops.get(1);
        assertThat(stream.turns()).isEqualTo(1);
        assertThat(stream.adoptionRate()).isNull();
        assertThat(JarvisLatencyService.byLoop(List.of())).allSatisfy(l -> assertThat(l.turns()).isZero());
    }

    @Test
    @DisplayName("F-257 / SF-257-01 — la boucle : absente = legacy, casse ignorée, hors liste refusée")
    void loopIsClosed() {
        assertThat(JarvisLatencyService.loop(null)).isEqualTo("legacy");
        assertThat(JarvisLatencyService.loop(" ")).isEqualTo("legacy");
        assertThat(JarvisLatencyService.loop("STREAM")).isEqualTo("stream");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JarvisLatencyService.loop("lune"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
