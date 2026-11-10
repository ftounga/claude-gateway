package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/** F-253 / SF-253-02, SF-253-03 — un nom dit (accents, tirets, transcription) rapproché d'un nom connu. */
class JarvisNameMatchTest {

    private static final List<String> NAMES = List.of("CAGIP", "Edenred", "data-platform", "agenor", "Orion Assurances");

    private static JarvisNameMatch.Result<String> match(String said) {
        return JarvisNameMatch.match(said, NAMES, Function.identity());
    }

    @Test
    void toleratesCaseAccentsDashesAndPhoneticSpelling() {
        assertThat(match("cagip").found()).isEqualTo("CAGIP");
        assertThat(match("Kajip").found()).isEqualTo("CAGIP");
        assertThat(match("agénor").found()).isEqualTo("agenor");
        assertThat(match("data platform").found()).isEqualTo("data-platform");
        assertThat(match("Orion").found()).isEqualTo("Orion Assurances");
        assertThat(match("Edenrede").found()).isEqualTo("Edenred");
    }

    @Test
    void unknownNameIsNoneAndAmbiguousNamesAreListed() {
        assertThat(match("Orange").none()).isTrue();
        assertThat(match("").none()).isTrue();
        JarvisNameMatch.Result<String> two = JarvisNameMatch.match("helios",
                List.of("helios-prod", "helios-test"), Function.identity());
        assertThat(two.found()).isNull();
        assertThat(two.ambiguous()).hasSize(2);
    }
}
