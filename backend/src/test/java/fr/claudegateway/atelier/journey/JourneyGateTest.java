package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-206 / SF-206-02 — la porte AGRÈGE les chantiers ouverts (décision PO). */
class JourneyGateTest {

    private static SubjectJourney focus(JourneyPhase phase, int planVersion, Integer validated) {
        SubjectJourney j = SubjectJourney.builder().userId(UUID.randomUUID()).workspaceId(UUID.randomUUID())
                .mode(JourneyMode.GUIDE).phase(phase).planVersion(planVersion).validatedVersion(validated)
                .chantierNumber(2).build();
        return j;
    }

    private static SubjectJourneyChantier other(int number, JourneyPhase phase, int planVersion, Integer validated) {
        return SubjectJourneyChantier.builder().number(number).status(SubjectJourneyChantier.OPEN).phase(phase)
                .planVersion(planVersion).validatedVersion(validated).build();
    }

    @Test
    @DisplayName("un seul chantier : comportement et message inchangés à l'octet près")
    void singleUnchanged() {
        SubjectJourney plan = focus(JourneyPhase.PLAN, 1, null);
        assertThat(JourneyGate.isClosed(plan)).isTrue();
        assertThat(JourneyGate.message(plan))
                .isEqualTo("Ce terminal est en mode Guidé, phase Plan : cette action attend la validation du plan.");
        assertThat(JourneyGate.refusal(plan, JourneyPlan.Risk.REVERSIBLE))
                .startsWith("Ce terminal est en mode Guidé, phase Plan")
                .doesNotContain("Chantier");
        assertThat(JourneyGate.isClosed(focus(JourneyPhase.EXECUTION, 2, 2))).isFalse();
        assertThat(JourneyGate.isClosed(focus(JourneyPhase.EXECUTION, 3, 2))).as("amendement non revalidé").isTrue();
    }

    @Test
    @DisplayName("un autre chantier en Exécution validé ouvre la porte, même si le focus investigue")
    void anyExecutingOpens() {
        SubjectJourney j = focus(JourneyPhase.INVESTIGATION, 0, null);
        j.setOtherOpenChantiers(List.of(other(1, JourneyPhase.EXECUTION, 1, 1)));
        assertThat(JourneyGate.isClosed(j)).isFalse();
        assertThat(JourneyGate.message(j)).isNull();
        assertThat(JourneyGate.refusal(j, JourneyPlan.Risk.EXTERNE)).isNull();
    }

    @Test
    @DisplayName("aucun chantier en Exécution : porte fermée, le message nomme les chantiers")
    void noneExecutingNamesThem() {
        SubjectJourney j = focus(JourneyPhase.INVESTIGATION, 0, null);
        j.setOtherOpenChantiers(List.of(other(1, JourneyPhase.PLAN, 2, null), other(3, JourneyPhase.EXECUTION, 2, 1)));
        assertThat(JourneyGate.isClosed(j)).isTrue();
        assertThat(JourneyGate.message(j)).isEqualTo("Ce terminal est en mode Guidé : aucun de ses chantiers "
                + "ouverts n'est en Exécution sur un plan validé (Chantier 1 · Plan, Chantier 2 · Investigation, "
                + "Chantier 3 · Exécution) : cette action attend la validation d'un plan.");
        assertThat(JourneyGate.refusal(j, JourneyPlan.Risk.REVERSIBLE))
                .contains("dès qu'UN chantier ouvert")
                .contains("Chantier 2 (focus) : Phase Investigation");
        assertThat(JourneyGate.refusal(j, JourneyPlan.Risk.LECTURE)).isNull();
        assertThat(JourneyGate.refusal(j, JourneyPlan.Risk.NOTES)).isNull();
    }

    @Test
    @DisplayName("Libre, jamais guidé ou clos : pas de porte")
    void noGate() {
        SubjectJourney libre = focus(JourneyPhase.PLAN, 1, null);
        libre.setMode(JourneyMode.LIBRE);
        libre.setOtherOpenChantiers(List.of(other(1, JourneyPhase.PLAN, 1, null)));
        assertThat(JourneyGate.isClosed(libre)).isFalse();
        assertThat(JourneyGate.isClosed(focus(null, 0, null))).isFalse();
        assertThat(JourneyGate.isClosed(focus(JourneyPhase.CLOS, 1, 1))).isFalse();
        assertThat(JourneyGate.isClosed(null)).isFalse();
    }
}
