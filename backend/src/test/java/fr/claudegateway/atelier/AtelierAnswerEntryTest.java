package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Le compte rendu des réponses rendu au modèle (F-164 / SF-164-01) : choix, réponse libre, mixte.
 */
class AtelierAnswerEntryTest {

    @Test
    void selectedOptionsAreRendered() {
        String out = AtelierAnswerEntry.compose(List.of(
                new AtelierAnswerEntry("Périmètre", List.of("Minimal", "Cœur"), null)));
        assertThat(out).contains("[Périmètre]").contains("Minimal, Cœur");
    }

    @Test
    void aFreeTextAnswerAloneIsAccepted() {
        // CA5 : l'option libre « autre » sans aucune option cochée est une réponse valide.
        String out = AtelierAnswerEntry.compose(List.of(
                new AtelierAnswerEntry("Autre", List.of(), "ma réponse à moi")));
        assertThat(out).contains("(réponse libre) ma réponse à moi");
    }

    @Test
    void selectedAndFreeTextAreBothRendered() {
        String out = AtelierAnswerEntry.compose(List.of(
                new AtelierAnswerEntry("Q", List.of("A"), "et aussi ceci")));
        assertThat(out).contains("A").contains("(réponse libre) et aussi ceci");
    }

    @Test
    void severalAnswersAreNumbered() {
        String out = AtelierAnswerEntry.compose(List.of(
                new AtelierAnswerEntry("Un", List.of("A"), null),
                new AtelierAnswerEntry("Deux", List.of("B"), null)));
        assertThat(out).contains("1.").contains("2.");
    }

    @Test
    void anEmptyEntryIsDetected() {
        assertThat(new AtelierAnswerEntry("", List.of(), "  ").isEmpty()).isTrue();
        assertThat(new AtelierAnswerEntry("", List.of("A"), null).isEmpty()).isFalse();
    }
}
