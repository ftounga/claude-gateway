package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class JourneyPlanTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    @Test
    @DisplayName("le plan reçu est normalisé : étape sans action ignorée, risque inconnu = modification réversible")
    void normalizes() throws Exception {
        JourneyPlan plan = JourneyPlan.fromToolInput(json("""
                [{"title":"Lire les logs de l'ingress","risk":"lecture","verify":"erreur 502 vue"},
                 {"risk":"EXTERNE"},
                 {"title":"Patcher la conf","risk":"???","rollback":"git revert"},
                 {"title":"Obtenir le certificat de Gino","risk":"externe","waits_on":"certificat-gino"}]"""));
        assertThat(plan.steps()).hasSize(3);
        assertThat(plan.steps().get(0).risk()).isEqualTo(JourneyPlan.Risk.LECTURE);
        assertThat(plan.steps().get(1).risk()).isEqualTo(JourneyPlan.Risk.REVERSIBLE);
        assertThat(plan.steps().get(2).waitsOn()).isEqualTo("certificat-gino");
        assertThat(plan.steps().get(2).risk()).isEqualTo(JourneyPlan.Risk.EXTERNE);
    }

    @Test
    @DisplayName("au-delà de 20 étapes, le plan est tronqué ; un tableau absent donne le plan vide")
    void bounds() throws Exception {
        StringBuilder raw = new StringBuilder("[");
        for (int i = 0; i < 25; i++) {
            raw.append(i == 0 ? "" : ",").append("{\"title\":\"étape ").append(i).append("\",\"risk\":\"NOTES\"}");
        }
        assertThat(JourneyPlan.fromToolInput(json(raw.append("]").toString())).steps())
                .hasSize(JourneyPlan.MAX_STEPS);
        assertThat(JourneyPlan.fromToolInput(null).isEmpty()).isTrue();
        assertThat(JourneyPlan.fromToolInput(json("{}")).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("aller-retour JSON fidèle ; un JSON illisible donne le plan vide, jamais une exception")
    void roundTrip() throws Exception {
        JourneyPlan plan = JourneyPlan.fromToolInput(json("""
                [{"title":"A","risk":"REVERSIBLE","verify":"v","rollback":"r","waits_on":"k"}]"""))
                .withStep(0, JourneyPlan.StepStatus.VERIFIE, "curl 200");
        assertThat(JourneyPlan.fromJson(plan.toJson())).isEqualTo(plan);
        assertThat(JourneyPlan.fromJson("pas du json").isEmpty()).isTrue();
        assertThat(JourneyPlan.fromJson(null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("un amendement marque les étapes nouvelles ou modifiées, et garde l'avancement des autres")
    void amendment() throws Exception {
        JourneyPlan v1 = JourneyPlan.fromToolInput(json("""
                [{"title":"A","risk":"LECTURE"},{"title":"B","risk":"REVERSIBLE"}]"""))
                .withStep(0, JourneyPlan.StepStatus.FAIT, null);
        JourneyPlan v2 = JourneyPlan.fromToolInput(json("""
                [{"title":"A","risk":"LECTURE"},{"title":"B bis","risk":"REVERSIBLE"},{"title":"C","risk":"NOTES"}]"""));
        assertThat(v2.changedSince(v1, 0)).isFalse();
        assertThat(v2.changedSince(v1, 1)).isTrue();
        assertThat(v2.changedSince(v1, 2)).isTrue();
        JourneyPlan carried = SubjectJourneyService.carryProgress(v2, v1);
        assertThat(carried.steps().get(0).status()).isEqualTo(JourneyPlan.StepStatus.FAIT);
        assertThat(carried.steps().get(1).status()).isEqualTo(JourneyPlan.StepStatus.A_FAIRE);
    }

    @Test
    @DisplayName("le parcours guidé rappelle le plan, sa version et s'il est validé")
    void turnNoteShowsPlan() throws Exception {
        JourneyPlan plan = JourneyPlan.fromToolInput(json("""
                [{"title":"Lire les logs","risk":"LECTURE"},{"title":"Obtenir le certificat","risk":"EXTERNE","waits_on":"cert-gino"}]"""));
        SubjectJourney journey = SubjectJourney.builder().userId(UUID.randomUUID()).workspaceId(UUID.randomUUID())
                .mode(JourneyMode.GUIDE).phase(JourneyPhase.PLAN).planJson(plan.toJson()).planVersion(1).build();
        String note = JourneyTurnNote.render(journey);
        assertThat(note).contains("Plan v1 — EN ATTENTE DE VALIDATION")
                .contains("1. [à faire] Lire les logs · lecture")
                .contains("attend : cert-gino");
        journey.setValidatedVersion(1);
        journey.setPhase(JourneyPhase.EXECUTION);
        assertThat(JourneyTurnNote.render(journey)).contains("Plan v1 — VALIDÉ");
        journey.setPlanVersion(2);
        assertThat(JourneyTurnNote.render(journey)).contains("AMENDEMENT EN ATTENTE DE VALIDATION");
    }
}
