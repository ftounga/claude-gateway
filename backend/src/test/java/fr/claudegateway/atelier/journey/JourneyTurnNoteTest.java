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
    @DisplayName("F-206 : plusieurs chantiers ouverts — focus détaillé d'abord, les autres résumés, borné")
    void severalChantiers() {
        SubjectJourney j = journey(JourneyMode.GUIDE, JourneyPhase.INVESTIGATION);
        j.setChantierNumber(2);
        j.setChantierTitle("Migration DNS");
        assertThat(JourneyTurnNote.render(j)).doesNotContain("Chantiers ouverts").doesNotContain("==");
        java.util.List<SubjectJourneyChantier> others = new java.util.ArrayList<>();
        others.add(SubjectJourneyChantier.builder().number(1).title("Ingress").status("OPEN")
                .phase(JourneyPhase.EXECUTION).planVersion(1).validatedVersion(1)
                .planJson("[{\"title\":\"a\",\"risk\":\"REVERSIBLE\",\"status\":\"FAIT\"},"
                        + "{\"title\":\"b\",\"risk\":\"REVERSIBLE\"}]")
                .build());
        for (int n = 3; n <= 10; n++) {
            others.add(SubjectJourneyChantier.builder().number(n).status("OPEN").phase(JourneyPhase.PLAN).build());
        }
        j.setOtherOpenChantiers(others);
        String note = JourneyTurnNote.render(j);
        assertThat(note).startsWith("--- Parcours du sujet ---\n")
                .contains("Chantiers ouverts : 10. Focus = Chantier 2")
                .contains("== Chantier 2 (focus) — Migration DNS ==\nMode GUIDÉ · phase : Investigation.")
                .contains("== Chantier 1 — Ingress · Exécution")
                .contains("== Chantier 1 — Ingress · Exécution · plan v1 validé · 1/2 étapes ==")
                .contains("== Chantier 7 — Chantier 7 · Plan ==")
                .doesNotContain("Chantier 8 —")
                .contains("… et 3 autre(s) chantier(s) ouvert(s).")
                .endsWith("---\n\n");
        assertThat(note.indexOf("Chantier 2 (focus)")).isLessThan(note.indexOf("Chantier 1 —"));
        j.setGuidedProposedAt(java.time.OffsetDateTime.now());
        assertThat(JourneyTurnNote.render(j)).contains("chantier de plus attend le choix");
    }

    @Test
    @DisplayName("F-222 / SF-222-03 : un chantier en Exécution sans geste depuis 3 jours n'est plus détaillé, il est nommé")
    void staleChantiersAreNamedNotDetailed() {
        SubjectJourney j = journey(JourneyMode.GUIDE, JourneyPhase.PLAN);
        j.setChantierNumber(9);
        java.time.OffsetDateTime old = java.time.OffsetDateTime.now().minusDays(5);
        java.util.List<SubjectJourneyChantier> others = new java.util.ArrayList<>();
        for (int n : new int[] {3, 6}) {
            others.add(SubjectJourneyChantier.builder().number(n).title("Zombie " + n).status("OPEN")
                    .phase(JourneyPhase.EXECUTION).planVersion(1).validatedVersion(1).updatedAt(old)
                    .planJson("[{\"title\":\"a\",\"risk\":\"REVERSIBLE\"}]").build());
        }
        others.add(SubjectJourneyChantier.builder().number(4).title("Vivant").status("OPEN")
                .phase(JourneyPhase.EXECUTION).updatedAt(java.time.OffsetDateTime.now()).build());
        // En Plan depuis longtemps : il attend un geste, il n'est pas « en sommeil ».
        others.add(SubjectJourneyChantier.builder().number(5).title("Attente").status("OPEN")
                .phase(JourneyPhase.PLAN).updatedAt(old).build());
        j.setOtherOpenChantiers(others);
        String note = JourneyTurnNote.render(j);
        assertThat(note).contains("== Chantier 4 — Vivant").contains("== Chantier 5 — Attente")
                .doesNotContain("Zombie 3").doesNotContain("Zombie 6")
                .contains("En sommeil (en Exécution sans geste depuis 3 jours ou plus, non détaillés) : chantier(s) 3, 6.")
                .doesNotContain("ACTEURS À PRÉCISER");
        assertThat(others.get(0).isStale(java.time.OffsetDateTime.now())).isTrue();
        assertThat(others.get(2).isStale(java.time.OffsetDateTime.now())).isFalse();
        assertThat(others.get(3).isStale(java.time.OffsetDateTime.now())).isFalse();
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
