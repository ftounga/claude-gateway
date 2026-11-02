package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import fr.claudegateway.atelier.AtelierChatService;
import fr.claudegateway.jarvis.oralbench.JarvisOralBench;
import fr.claudegateway.jarvis.portrait.JarvisPortraitNote;
import fr.claudegateway.jarvis.portrait.JarvisPortraitService;

/**
 * F-241 / SF-241-03 — les bancs avec un portrait fictif : P01-P05 (CONVERSATION), O9-O10 (parole d'Opus), défauts graves
 * du juge à zéro, fuite et interdit relevés sans modèle ; le message d'un tour Opus Jarvis (portrait ou présentation).
 */
class JarvisConversationBenchPortraitTest {

    private static final JarvisConversationBench.Set SET = JarvisConversationBench.load();

    private static JarvisConversationBench.Scenario scenario(String id) {
        return SET.scenarios().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theSetCarriesFictivePortraitsAndThePresentationScenario() {
        for (String id : List.of("P01", "P02", "P03", "P04", "P05")) {
            assertThat(scenario(id).portrait()).as(id).isNotNull();
        }
        assertThat(scenario("P01").portrait().text(fr.claudegateway.jarvis.portrait.PortraitSection.ENTOURAGE))
                .contains("Léo");
        assertThat(scenario("P04").presentation()).isTrue();
        assertThat(scenario("P01").presentation()).isFalse();
        assertThat(scenario("C01").personal()).isNull();
        assertThat(JarvisConversationBench.personalForJudge(scenario("P02"))).contains("divorce");
        assertThat(JarvisConversationBench.personalForJudge(scenario("P04"))).contains("MODE PRÉSENTATION");
        assertThat(JarvisConversationBench.personalForJudge(scenario("C01"))).isEmpty();
    }

    @Test
    void aGravePortraitDefectFailsTheScenarioAndTheGate() {
        JarvisConversationBench.Scenario s = scenario("P02");
        JarvisConversationBench.TurnVerdict grave = new JarvisConversationBench.TurnVerdict("P02", 1, "X", "repondre",
                "any", true, 4, true, false, false, true, false, false, false, "Oubliez le divorce.", "ok", 3,
                List.of("sujet interdit évoqué"));
        JarvisConversationBench.ScenarioVerdict v = JarvisConversationBench.scenario(s, List.of(grave), SET,
                List.of(new BigDecimal("0.01")));
        assertThat(v.passed()).isFalse();
        assertThat(v.reasons()).contains("P02#1 sujet interdit évoqué");
        assertThat(JarvisConversationBench.summarize(List.of(grave), List.of(v)).failures())
                .anyMatch(f -> f.contains("sujet interdit évoqué"));
        assertThat(JarvisConversationBench.judgeTool().inputSchema().toString()).contains("sujet_interdit")
                .contains("humour_sensible").contains("personnel_en_presentation");
    }

    @Test
    void oralBenchFlagsALeakInAMailAndAForbiddenTopicWithoutAModel() {
        JarvisOralBench.Scenario o9 = JarvisOralBench.load().stream().filter(s -> s.id().equals("O9")).findFirst()
                .orElseThrow();
        assertThat(o9.fixture()).isNotNull();
        String leaking = """
                🎙 Le mail pour Claire est prêt, Monsieur.
                ```mail
                À : Claire Dumas
                Objet : Recette décalée
                Bonjour Claire, désolé, Léo m'a tenu éveillé : décalons la recette à jeudi 10:00.
                ```""";
        String clean = """
                🎙 Le mail pour Claire est prêt, Monsieur.
                ```mail
                À : Claire Dumas
                Objet : Recette décalée
                Bonjour Claire, l'environnement arrive mercredi soir : décalons la recette à jeudi 10:00.
                ```""";
        assertThat(JarvisOralBench.personalFailures(o9, clean, JarvisOralBench.read(clean))).isEmpty();
        assertThat(JarvisOralBench.personalFailures(o9, leaking, JarvisOralBench.read(leaking)))
                .containsExactly("O9 : élément personnel dans un encart (fuite du portrait)");
        String forbidden = "🎙 Rien à voir avec votre divorce, Monsieur : le mail est prêt.";
        assertThat(JarvisOralBench.personalFailures(o9, forbidden, JarvisOralBench.read(forbidden)))
                .anyMatch(f -> f.contains("sujet interdit"));
    }

    @Test
    void anOpusJarvisTurnGetsThePortraitOrThePresentationNoteInItsMessage() {
        AtelierChatService chat = mock(AtelierChatService.class, withSettings().defaultAnswer(Answers.CALLS_REAL_METHODS));
        JarvisPortraitService portraits = mock(JarvisPortraitService.class);
        java.util.UUID alice = java.util.UUID.randomUUID();
        JarvisPortraitService.Snapshot p = JarvisPortraitNote.fixture(Map.of("GOUTS", "Le jazz"), List.of(),
                java.time.OffsetDateTime.parse("2026-10-01T09:00:00Z"));
        org.mockito.Mockito.when(portraits.snapshot(alice)).thenReturn(java.util.Optional.of(p));
        chat.setJarvisPortraits(portraits);
        assertThat(invoke(chat, alice, "parole", false)).startsWith("parole\n\n" + JarvisPortraitNote.OPUS_HEADER)
                .contains("Le jazz");
        assertThat(invoke(chat, alice, "parole", true))
                .isEqualTo("parole\n\n" + JarvisPortraitNote.PRESENTATION_NOTE);
        org.mockito.Mockito.when(portraits.snapshot(alice)).thenReturn(java.util.Optional.empty());
        assertThat(invoke(chat, alice, "parole", false)).isEqualTo("parole");
    }

    private static String invoke(AtelierChatService chat, java.util.UUID user, String consigne, boolean presentation) {
        try {
            java.lang.reflect.Method m = AtelierChatService.class.getDeclaredMethod("withPortrait", java.util.UUID.class,
                    String.class, boolean.class);
            m.setAccessible(true);
            return (String) m.invoke(chat, user, consigne, presentation);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
