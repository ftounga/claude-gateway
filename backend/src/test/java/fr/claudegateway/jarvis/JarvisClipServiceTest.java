package fr.claudegateway.jarvis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-233 / SF-233-11 — le répertoire des formules de Jarvis : varié, au vouvoiement, « Monsieur » rare. */
class JarvisClipServiceTest {

    private static final List<String> TUTOIEMENT = List.of("tu", "te", "toi", "ton", "ta", "tes", "t'");

    @Test
    @DisplayName("aucune formule ne tutoie (D-PO-8 : « Monsieur » et vouvoiement)")
    void noTutoiement() {
        JarvisClipService.CLIPS.values().forEach(text -> {
            List<String> words = Arrays.asList(text.toLowerCase(Locale.ROOT).replace('’', '\'').replace("'", "' ")
                    .split("[^\\p{L}']+"));
            assertThat(words).as(text).doesNotContainAnyElementsOf(TUTOIEMENT);
        });
    }

    @Test
    @DisplayName("le répertoire est assez large pour ne jamais répéter parmi les 5 dernières ; « Monsieur » rare")
    void repertoire() {
        assertThat(JarvisClipService.CLIPS).containsKeys("oui", "oui-court", "je-vous-ecoute", "je-regarde",
                "une-seconde", "je-m-en-occupe", "un-instant");
        assertThat(JarvisClipService.CLIPS.size()).isGreaterThanOrEqualTo(12);
        long monsieur = JarvisClipService.CLIPS.values().stream().filter(t -> t.contains("Monsieur")).count();
        assertThat(monsieur).isLessThanOrEqualTo(2);
        assertThat(JarvisClipService.CLIPS.get("je-vous-ecoute")).isEqualTo("Je vous écoute.");
    }
}
