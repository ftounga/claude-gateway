package fr.claudegateway.jarvis.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fr.claudegateway.jarvis.fastbrain.JarvisFastBrainService;

/** F-247 / SF-247-01 — classification déterministe des questions d'état et de leurs réponses. */
class JarvisStateQuestionTest {

    @ParameterizedTest
    @ValueSource(strings = {"Où en est PROJ-123 ?", "c'est quoi la version déployée en prod", "la MR !42 est passée ?",
            "qu'est-ce qui m'attend aujourd'hui", "le pipeline de main est vert ?", "qu'a dit Rémi sur la migration",
            "Rémi m'a dit quoi hier ?", "qu'est-ce qui est bloqué chez CAGIP", "j'ai des messages non lus ?",
            "quel est le statut du ticket SSO", "où ça en est la PR #17"})
    @DisplayName("questions d'état reconnues")
    void stateQuestions(String text) {
        assertThat(JarvisStateQuestion.isStateQuestion(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bonjour Jarvis", "raconte-moi une blague", "merci, c'est parfait",
            "lance les tests et corrige", "quelle heure est-il"})
    @DisplayName("hors question d'état")
    void notStateQuestions(String text) {
        assertThat(JarvisStateQuestion.isStateQuestion(text)).isFalse();
    }

    @Test
    void nullOrBlank() {
        assertThat(JarvisStateQuestion.isStateQuestion(null)).isFalse();
        assertThat(JarvisStateQuestion.isStateQuestion("  ")).isFalse();
    }

    private static JarvisFastBrainService.Outcome answer(String text) {
        return new JarvisFastBrainService.Outcome(JarvisFastBrainService.Kind.ANSWER, text, null, null, "m", 1,
                BigDecimal.ZERO);
    }

    private static JarvisFastBrainService.Outcome delegated() {
        return new JarvisFastBrainService.Outcome(JarvisFastBrainService.Kind.DELEGATE, "Je regarde.", null, null,
                "m", 1, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("réponse : lexique fermé ⇒ UNKNOWN ; délégation poste éteint ⇒ UNKNOWN ; sinon STALE/FRESH ; hors question ⇒ NA")
    void answers() {
        assertThat(JarvisStateTurnMeter.answer(true, true, answer("Je ne sais pas, le poste est éteint.")))
                .isEqualTo("UNKNOWN");
        assertThat(JarvisStateTurnMeter.answer(true, true, answer("Je n'ai aucune information sur ce ticket.")))
                .isEqualTo("UNKNOWN");
        assertThat(JarvisStateTurnMeter.answer(true, true, delegated())).isEqualTo("UNKNOWN");
        assertThat(JarvisStateTurnMeter.answer(true, false, delegated())).isEqualTo("NA");
        assertThat(JarvisStateTurnMeter.answer(true, true, answer("Il est en revue depuis hier 17 h 42.")))
                .isEqualTo("KNOWN_STALE");
        assertThat(JarvisStateTurnMeter.answer(true, false, answer("Il est en revue."))).isEqualTo("KNOWN_FRESH");
        assertThat(JarvisStateTurnMeter.answer(false, true, answer("Bonjour."))).isEqualTo("NA");
        assertThat(JarvisStateTurnMeter.answer(true, true, null)).isEqualTo("NA");
    }
}
