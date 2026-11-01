package fr.claudegateway.jarvis.audition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.jarvis.JarvisVoiceService;

/** Les candidats et le mélange de l'audition (F-233 / SF-233-07). */
class JarvisAuditionServiceTest {

    private JarvisAuditionService service(JarvisAuditionProperties properties, JarvisVoiceChoiceRepository choices) {
        return new JarvisAuditionService(properties, mock(JarvisVoiceService.class), choices, Clock.systemUTC());
    }

    @Test
    @DisplayName("candidats imposés par le PO ; la voix décrite s'ajoute si son identifiant est fourni")
    void candidates() {
        JarvisVoiceChoiceRepository choices = mock(JarvisVoiceChoiceRepository.class);
        assertThat(service(new JarvisAuditionProperties(null, null), choices).candidates())
                .extracting(JarvisAuditionService.Candidate::name)
                .containsExactly("Daniel (ElevenLabs)", "George (ElevenLabs)", "Onyx (OpenAI)");
        assertThat(service(new JarvisAuditionProperties(null, "Zx9DesignedVoice01"), choices).candidates())
                .hasSize(4).last().satisfies(c -> {
                    assertThat(c.voice().provider()).isEqualTo("elevenlabs");
                    assertThat(c.voice().voice()).isEqualTo("Zx9DesignedVoice01");
                });
    }

    @Test
    @DisplayName("mélange stable pour un compte et un tour ; il change avec le compte ou après un choix")
    void stableShuffle() {
        JarvisVoiceChoiceRepository choices = mock(JarvisVoiceChoiceRepository.class);
        JarvisAuditionService service = service(new JarvisAuditionProperties(List.of("openai:a:A", "openai:b:B",
                "openai:c:C", "openai:d:D", "openai:e:E", "openai:f:F"), null), choices);
        UUID alice = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(choices.countByUserId(any())).thenReturn(0L);
        List<JarvisAuditionService.Candidate> first = service.ordered(alice);
        assertThat(service.ordered(alice)).isEqualTo(first);
        assertThat(JarvisAuditionService.seed(alice, 0)).isNotEqualTo(JarvisAuditionService.seed(alice, 1));
        assertThat(JarvisAuditionService.seed(alice, 0))
                .isNotEqualTo(JarvisAuditionService.seed(UUID.fromString("00000000-0000-0000-0000-000000000002"), 0));
        assertThat(JarvisAuditionService.label(0)).isEqualTo("A");
        assertThat(JarvisAuditionService.label(2)).isEqualTo("C");
    }
}
