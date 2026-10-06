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
    @DisplayName("SF-176-11 : en Libre après un chantier clos, une ligne invite à proposer un NOUVEAU chantier")
    void closedChantierInvitesNext() {
        SubjectJourney closed = journey(JourneyMode.LIBRE, JourneyPhase.CLOS);
        closed.setChantierNumber(2);
        assertThat(JourneyTurnNote.render(closed)).contains("chantier 2 clos").contains("propose_guided_mode");
        closed.setGuidedDeclinedAt(java.time.OffsetDateTime.now());
        assertThat(JourneyTurnNote.render(closed)).isEmpty();
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

    @Test
    @DisplayName("SF-176-02 : premier message en Libre → qualifier ; après « rester libre » → rien")
    void firstTurnQualifies() {
        SubjectJourney libre = journey(JourneyMode.LIBRE, null);
        assertThat(JourneyTurnNote.render(libre, true)).contains("qualifie la demande")
                .contains("propose_guided_mode");
        assertThat(JourneyTurnNote.render(libre, false)).isEmpty();
        assertThat(JourneyTurnNote.render(null, true)).contains("qualifie la demande");
        libre.setGuidedDeclinedAt(java.time.OffsetDateTime.now());
        assertThat(JourneyTurnNote.render(libre, true)).isEmpty();
    }

    @Test
    @DisplayName("SF-176-02 : une proposition en attente est rappelée à l'agent")
    void pendingProposal() {
        SubjectJourney libre = journey(JourneyMode.LIBRE, null);
        libre.setGuidedProposedAt(java.time.OffsetDateTime.now());
        assertThat(JourneyTurnNote.render(libre, false)).contains("attend le choix");
    }

    @Test
    @DisplayName("SF-176-02 : sans droit d'espace, aucun outil du parcours")
    void catalogGuard() {
        assertThat(JourneyToolCatalog.none().toolsFor(UUID.randomUUID(), null)).isEmpty();
        assertThat(JourneyToolCatalog.isJourneyTool("propose_guided_mode")).isTrue();
        assertThat(JourneyToolCatalog.isJourneyTool("bash")).isFalse();
    }
}
