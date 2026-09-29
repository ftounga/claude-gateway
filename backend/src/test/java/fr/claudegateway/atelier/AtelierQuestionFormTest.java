package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validation du lot de questions de l'outil {@code demander} (F-164 / SF-164-01) : un lot valide est
 * accepté, un lot mal formé <b>échoue bruyamment</b> avec un message qui dit quoi corriger.
 */
class AtelierQuestionFormTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void aValidBatchIsParsed() throws Exception {
        AtelierQuestionForm form = AtelierQuestionForm.from(json("""
                {"questions":[
                  {"header":"Périmètre","question":"Quel périmètre ?","multiSelect":true,
                   "options":[
                     {"label":"Minimal","description":"le socle","recommended":true},
                     {"label":"Complet"}]}]}
                """));

        assertThat(form.questions()).hasSize(1);
        AtelierQuestionForm.Question q = form.questions().get(0);
        assertThat(q.header()).isEqualTo("Périmètre");
        assertThat(q.question()).isEqualTo("Quel périmètre ?");
        assertThat(q.multiSelect()).isTrue();
        assertThat(q.options()).hasSize(2);
        assertThat(q.options().get(0).recommended()).isTrue();
        assertThat(q.options().get(1).recommended()).isFalse();
    }

    @Test
    void multiSelectDefaultsToFalseAndHeaderIsDerived() throws Exception {
        AtelierQuestionForm form = AtelierQuestionForm.from(json("""
                {"questions":[{"question":"A ou B ?","options":[{"label":"A"},{"label":"B"}]}]}
                """));

        assertThat(form.questions().get(0).multiSelect()).isFalse();
        assertThat(form.questions().get(0).header()).isEqualTo("A ou B ?");
    }

    @Test
    void anEmptyOrMissingBatchIsRejected() {
        assertThatThrownBy(() -> AtelierQuestionForm.from(null))
                .isInstanceOf(AtelierQuestionRejectedException.class);
        assertThatThrownBy(() -> AtelierQuestionForm.from(json("{\"questions\":[]}")))
                .isInstanceOf(AtelierQuestionRejectedException.class);
    }

    @Test
    void tooManyQuestionsAreRejected() throws Exception {
        String five = "{\"questions\":["
                + "{\"question\":\"1\",\"options\":[{\"label\":\"a\"}]},"
                + "{\"question\":\"2\",\"options\":[{\"label\":\"a\"}]},"
                + "{\"question\":\"3\",\"options\":[{\"label\":\"a\"}]},"
                + "{\"question\":\"4\",\"options\":[{\"label\":\"a\"}]},"
                + "{\"question\":\"5\",\"options\":[{\"label\":\"a\"}]}]}";
        assertThatThrownBy(() -> AtelierQuestionForm.from(json(five)))
                .isInstanceOf(AtelierQuestionRejectedException.class);
    }

    @Test
    void aQuestionWithoutTextIsRejected() throws Exception {
        assertThatThrownBy(() -> AtelierQuestionForm.from(json(
                "{\"questions\":[{\"question\":\"  \",\"options\":[{\"label\":\"a\"}]}]}")))
                .isInstanceOf(AtelierQuestionRejectedException.class);
    }

    @Test
    void aQuestionWithoutOptionsIsRejected() throws Exception {
        assertThatThrownBy(() -> AtelierQuestionForm.from(json(
                "{\"questions\":[{\"question\":\"Q\",\"options\":[]}]}")))
                .isInstanceOf(AtelierQuestionRejectedException.class);
    }

    @Test
    void anOptionWithoutLabelIsRejected() throws Exception {
        assertThatThrownBy(() -> AtelierQuestionForm.from(json(
                "{\"questions\":[{\"question\":\"Q\",\"options\":[{\"description\":\"x\"}]}]}")))
                .isInstanceOf(AtelierQuestionRejectedException.class);
    }

    @Test
    void moreThanOneRecommendedOptionIsRejected() throws Exception {
        assertThatThrownBy(() -> AtelierQuestionForm.from(json("""
                {"questions":[{"question":"Q","options":[
                  {"label":"A","recommended":true},
                  {"label":"B","recommended":true}]}]}
                """)))
                .isInstanceOf(AtelierQuestionRejectedException.class);
    }
}
