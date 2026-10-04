package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JourneyTurnNoteTest {

    private static SubjectJourney journey(JourneyMode mode, JourneyPhase phase) {
        return SubjectJourney.builder().userId(UUID.randomUUID()).workspaceId(UUID.randomUUID())
                .mode(mode).phase(phase).build();
    }

    @Test
    @DisplayName("Libre : rien — la consigne est inchangée à l'octet près (Q4)")
    void libreSaysNothing() {
        assertThat(JourneyTurnNote.render(null)).isEmpty();
        assertThat(JourneyTurnNote.render(journey(JourneyMode.LIBRE, null))).isEmpty();
        assertThat(JourneyTurnNote.render(journey(JourneyMode.LIBRE, JourneyPhase.PLAN))).isEmpty();
    }

    @Test
    @DisplayName("Guidé : le mode et la phase, dans un bloc daté de rien (pas de volatil inutile)")
    void guidedSaysPhase() {
        String note = JourneyTurnNote.render(journey(JourneyMode.GUIDE, JourneyPhase.INVESTIGATION));
        assertThat(note).startsWith("--- Parcours du sujet ---\n")
                .contains("Mode GUIDÉ · phase : Investigation.")
                .endsWith("---\n\n");
    }

    @Test
    @DisplayName("le mode se lit avec ou sans accent")
    void parseMode() {
        assertThat(JourneyMode.parse("guidé")).isEqualTo(JourneyMode.GUIDE);
        assertThat(JourneyMode.parse(" libre ")).isEqualTo(JourneyMode.LIBRE);
        assertThat(JourneyMode.parse("x")).isNull();
        assertThat(JourneyMode.parse(null)).isNull();
    }
}
