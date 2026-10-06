package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Contrat SSE {@code question_resolved} (F-164 / SF-164-06) : {@code defaults} additif, absent hors timeout. */
class StreamQuestionResolvedJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsAreEmittedOnlyWhenPresent() throws Exception {
        String timeout = mapper.writeValueAsString(new AtelierChatController.StreamQuestionResolved(
                "c1", "timeout", List.of("Base : Postgres")));
        String answered = mapper.writeValueAsString(new AtelierChatController.StreamQuestionResolved(
                "c1", "answered", null));

        assertThat(timeout).contains("\"defaults\":[\"Base : Postgres\"]");
        assertThat(answered).isEqualTo("{\"callId\":\"c1\",\"status\":\"answered\"}");
    }
}
