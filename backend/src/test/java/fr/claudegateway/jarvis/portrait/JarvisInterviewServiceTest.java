package fr.claudegateway.jarvis.portrait;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** F-241 / SF-241-02 — cadence de la proposition (D13), lecture du brouillon, repli brut, bornes. */
class JarvisInterviewServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-02T10:00:00Z");

    @Test
    void offerOnceThenEverySevenDaysAtMostThreeTimes() {
        assertThat(JarvisInterviewService.eligible(true, 0, null, NOW)).isTrue();
        assertThat(JarvisInterviewService.eligible(true, 1, NOW.minusDays(6), NOW)).isFalse();
        assertThat(JarvisInterviewService.eligible(true, 1, NOW.minusDays(7), NOW)).isTrue();
        assertThat(JarvisInterviewService.eligible(true, 3, NOW.minusDays(30), NOW)).isFalse();
        assertThat(JarvisInterviewService.eligible(false, 0, null, NOW)).isFalse();
    }

    @Test
    void parsesOnlyKnownSectionsAndBoundedSouvenirs() throws Exception {
        var input = new ObjectMapper().readTree("""
                {"sections":{"QUI":"Chef de projet","SECRETS":"x","GOUTS":"  "},
                 "souvenirs":[{"texte":"Naissance de Léa en mars","sensible":true},{"texte":"","sensible":false}]}""");
        JarvisInterviewService.Draft draft = JarvisInterviewService.parse(input);
        assertThat(draft.sections()).containsOnlyKeys("QUI");
        assertThat(draft.souvenirs()).hasSize(1);
        assertThat(draft.souvenirs().get(0).source()).isEqualTo("ENTRETIEN");
        assertThat(draft.souvenirs().get(0).sensitive()).isTrue();
    }

    @Test
    void rawFallbackPutsEachAnswerInItsQuestionSection() {
        JarvisInterviewService.Draft draft = JarvisInterviewService.raw(List.of(
                new JarvisInterviewService.Answer("Quel est votre métier, votre rôle aujourd'hui ?", "Chef de projet"),
                new JarvisInterviewService.Answer("Y a-t-il des sujets que je ne dois jamais aborder ?", "Le divorce"),
                new JarvisInterviewService.Answer("Quel humour vous fait rire ?", "Absurde")));
        assertThat(draft.sections()).containsEntry("QUI", "Chef de projet").containsEntry("INTERDITS", "Le divorce")
                .containsEntry("HUMOUR", "Absurde");
    }

    @Test
    void skippedAnswersAreDroppedAndBoundsEnforced() {
        assertThat(JarvisInterviewService.validate(List.of(new JarvisInterviewService.Answer("Q ?", "  "))))
                .isEmpty();
        List<JarvisInterviewService.Answer> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            many.add(new JarvisInterviewService.Answer("Q", "R"));
        }
        assertThatThrownBy(() -> JarvisInterviewService.validate(many))
                .isInstanceOf(JarvisPortraitExceptions.InvalidPortraitException.class);
        assertThatThrownBy(() -> JarvisInterviewService.validate(List.of(
                new JarvisInterviewService.Answer("Q", "x".repeat(2_001)))))
                .isInstanceOf(JarvisPortraitExceptions.InvalidPortraitException.class);
    }
}
