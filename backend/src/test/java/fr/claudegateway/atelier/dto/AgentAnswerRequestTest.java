package fr.claudegateway.atelier.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Contraintes de validation du corps de réponse (F-164 / SF-164-01) : callId requis, au moins une
 * réponse, et chaque réponse porte un choix ou un texte libre — sinon 400.
 */
class AgentAnswerRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void open() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void aValidBodyPasses() {
        AgentAnswerRequest body = new AgentAnswerRequest("call-1",
                List.of(new AgentAnswerRequest.Answer("H", List.of("A"), null)));
        assertThat(validator.validate(body)).isEmpty();
    }

    @Test
    void aFreeTextOnlyAnswerPasses() {
        AgentAnswerRequest body = new AgentAnswerRequest("call-1",
                List.of(new AgentAnswerRequest.Answer("H", List.of(), "ma réponse")));
        assertThat(validator.validate(body)).isEmpty();
    }

    @Test
    void aBlankCallIdIsRejected() {
        AgentAnswerRequest body = new AgentAnswerRequest("  ",
                List.of(new AgentAnswerRequest.Answer("H", List.of("A"), null)));
        assertThat(validator.validate(body)).isNotEmpty();
    }

    @Test
    void anEmptyAnswerListIsRejected() {
        AgentAnswerRequest body = new AgentAnswerRequest("call-1", List.of());
        assertThat(validator.validate(body)).isNotEmpty();
    }

    @Test
    void anAnswerWithNeitherChoiceNorTextIsRejected() {
        AgentAnswerRequest body = new AgentAnswerRequest("call-1",
                List.of(new AgentAnswerRequest.Answer("H", List.of(), "   ")));
        assertThat(validator.validate(body)).isNotEmpty();
    }
}
