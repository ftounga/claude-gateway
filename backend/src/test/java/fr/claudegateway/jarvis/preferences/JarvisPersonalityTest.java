package fr.claudegateway.jarvis.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.veille.JarvisWatchAlert;
import fr.claudegateway.atelier.veille.JarvisWatchAlertRepository;
import fr.claudegateway.jarvis.JarvisOralNote;
import fr.claudegateway.jarvis.fastbrain.JarvisFastBrainPrompt;
import fr.claudegateway.jarvis.fastbrain.JarvisFastGuards;

/** F-235 / SF-235-09 (D18) — la personnalité : variantes figées, ton sobre, aucune imitation, préférences. */
class JarvisPersonalityTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:15:00Z"), ZoneOffset.UTC);

    @Test
    void theFastBrainSystemPromptHasTwoFrozenVariants() {
        assertThat(JarvisFastBrainPrompt.system(JarvisHumour.LIGHT))
                .isEqualTo(JarvisFastBrainPrompt.system(JarvisHumour.LIGHT))
                .isEqualTo(JarvisFastBrainPrompt.system())
                .startsWith(JarvisFastBrainPrompt.CORE).contains("pince-sans-rire").contains("au plus UNE touche");
        assertThat(JarvisFastBrainPrompt.system(JarvisHumour.NONE)).startsWith(JarvisFastBrainPrompt.CORE)
                .contains("Humour : aucun").doesNotContain("pince-sans-rire")
                .isNotEqualTo(JarvisFastBrainPrompt.system(JarvisHumour.LIGHT));
        for (JarvisHumour h : JarvisHumour.values()) {
            assertThat(JarvisFastBrainPrompt.system(h)).doesNotContain("2026").doesNotContain("Maintenant :");
        }
    }

    @Test
    void theOralNoteCarriesThePersonalityAndTheSoberNoteInTheMessage() {
        String light = JarvisOralNote.appendTo("parole", JarvisHumour.LIGHT, false);
        assertThat(light).startsWith(JarvisOralNote.appendTo("parole")).endsWith(JarvisOralNote.PERSONALITY_LIGHT);
        String none = JarvisOralNote.appendTo("parole", JarvisHumour.NONE, true);
        assertThat(none).contains(JarvisOralNote.PERSONALITY_NONE).endsWith(JarvisSobriety.NOTE)
                .doesNotContain("pince-sans-rire");
        // Les variantes sont figées : même entrée, mêmes octets.
        assertThat(JarvisOralNote.appendTo("x", JarvisHumour.LIGHT, true))
                .isEqualTo(JarvisOralNote.appendTo("x", JarvisHumour.LIGHT, true));
    }

    @Test
    void soberOnTroubleWordsOrARecentAlert() {
        JarvisSobriety sobriety = new JarvisSobriety(clock);
        assertThat(sobriety.sober(UUID.randomUUID(), "On a un incident en production")).isTrue();
        assertThat(sobriety.sober(UUID.randomUUID(), "La bascule a échoué ? Une erreur ?")).isTrue();
        assertThat(sobriety.sober(UUID.randomUUID(), "Bonjour Jarvis")).isFalse();

        JarvisWatchAlertRepository alerts = mock(JarvisWatchAlertRepository.class);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        when(alerts.findByUserIdAndCreatedAtGreaterThanEqual(any(), any())).thenReturn(List.of());
        when(alerts.findByUserIdAndCreatedAtGreaterThanEqual(alice, OffsetDateTime.now(clock).minusHours(2)))
                .thenReturn(List.of(new JarvisWatchAlert()));
        sobriety.setAlerts(alerts);
        assertThat(sobriety.sober(alice, "Bonjour Jarvis")).isTrue();
        assertThat(sobriety.sober(bob, "Bonjour Jarvis")).isFalse();
    }

    @Test
    void neverImitatesTheWork() {
        assertThat(JarvisFastGuards.imitates("Comme dirait Monsieur Stark, tout va bien.", "", "")).isTrue();
        assertThat(JarvisFastGuards.imitates("Digne des Avengers.", "", "")).isTrue();
        assertThat(JarvisFastGuards.imitates("Un vrai Iron Man.", "", "")).isTrue();
        assertThat(JarvisFastGuards.imitates("Le client Marvel attend.", "client Marvel", "")).isFalse();
        assertThat(JarvisFastGuards.imitates("Le chantier avance.", "", "")).isFalse();
    }

    @Test
    void preferencesDefaultToLightAndStayPerAccount() {
        Map<UUID, JarvisPreference> rows = new HashMap<>();
        JarvisPreferenceRepository repository = mock(JarvisPreferenceRepository.class);
        when(repository.findById(any())).thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
        when(repository.save(any())).thenAnswer(i -> {
            JarvisPreference p = i.getArgument(0);
            rows.put(p.getUserId(), p);
            return p;
        });
        JarvisPreferenceService service = new JarvisPreferenceService(repository, clock);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        assertThat(service.humour(alice)).isEqualTo(JarvisHumour.LIGHT);
        service.setHumour(alice, JarvisHumour.NONE);
        assertThat(service.humour(alice)).isEqualTo(JarvisHumour.NONE);
        assertThat(service.humour(bob)).isEqualTo(JarvisHumour.LIGHT);
        assertThat(JarvisHumour.parse("aucun")).isEqualTo(JarvisHumour.NONE);
        assertThat(JarvisHumour.parse("léger")).isEqualTo(JarvisHumour.LIGHT);
        assertThat(JarvisHumour.parse("beaucoup")).isNull();
    }

    @Test
    void theTwentyQuestionSetIsSyntheticAndBalanced() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/jarvis/personality-questions.json")) {
            JsonNode set = new ObjectMapper().readTree(in);
            JsonNode questions = set.get("questions");
            assertThat(questions).hasSize(20);
            Map<String, Integer> byCategory = new HashMap<>();
            questions.forEach(q -> byCategory.merge(q.get("category").asText(), 1, Integer::sum));
            assertThat(byCategory.get("ALERT")).isGreaterThanOrEqualTo(5);
            assertThat(byCategory.get("SENSITIVE")).isGreaterThanOrEqualTo(3);
            assertThat(byCategory.get("ACTION")).isGreaterThanOrEqualTo(4);
            assertThat(set.get("context").asText()).isNotBlank();
        }
    }
}
