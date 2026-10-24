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

    private static String steps(int n) {
        StringBuilder raw = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            raw.append(i == 0 ? "" : ",").append("{\"title\":\"étape ").append(i).append("\",\"risk\":\"NOTES\"}");
        }
        return raw.append("]").toString();
    }

    @Test
    @DisplayName("SF-208-01 : 40 étapes passent en entier ; 41 sont REFUSÉES avec un message, jamais tronquées")
    void bounds() throws Exception {
        assertThat(JourneyPlan.fromToolInput(json(steps(40))).steps()).hasSize(JourneyPlan.MAX_STEPS).hasSize(40);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JourneyPlan.fromToolInput(json(steps(41))))
                .isInstanceOf(InvalidJourneyException.class)
                .hasMessageContaining("41 étapes : 40 au plus")
                .hasMessageContaining("update_sub_steps");
        assertThat(JourneyPlan.fromToolInput(null).isEmpty()).isTrue();
        assertThat(JourneyPlan.fromToolInput(json("{}")).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("SF-208-01 : depends_on validé (antérieures seulement), trié, sans doublon, relu à l'identique")
    void dependsOn() throws Exception {
        JourneyPlan plan = JourneyPlan.fromToolInput(json("""
                [{"title":"A","risk":"LECTURE"},{"title":"B","risk":"LECTURE"},
                 {"title":"C","risk":"EXTERNE","depends_on":[2,1,2]},{"title":"D","risk":"NOTES","depends_on":"1, 3"}]"""));
        assertThat(plan.steps().get(2).dependsOn()).containsExactly(1, 2);
        assertThat(plan.steps().get(3).dependsOn()).containsExactly(1, 3);
        assertThat(plan.steps().get(0).dependsOn()).isEmpty();
        assertThat(JourneyPlan.fromJson(plan.toJson())).isEqualTo(plan);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JourneyPlan.fromToolInput(json("""
                [{"title":"A","risk":"LECTURE"},{"title":"B","risk":"LECTURE","depends_on":[2]}]""")))
                .isInstanceOf(InvalidJourneyException.class)
                .hasMessageContaining("Étape 2 : depends_on cite « 2 »").hasMessageContaining("(1 à 1)");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JourneyPlan.fromToolInput(json("""
                [{"title":"A","risk":"LECTURE","depends_on":[0]}]""")))
                .hasMessageContaining("la première étape ne dépend d'aucune autre");
    }

    @Test
    @DisplayName("SF-208-01 : sous-étapes bornées ; « en cours » dérivé ; un plan d'avant F-208 se réécrit à l'octet")
    void subSteps() throws Exception {
        java.util.List<JourneyPlan.SubStep> subs = JourneyPlan.subStepsFromToolInput(json("""
                [{"title":"Sauvegarder","done":true,"evidence":"dump ok"},{"done":true},{"title":"Restaurer","done":false}]"""));
        assertThat(subs).hasSize(2);
        JourneyPlan plan = JourneyPlan.fromToolInput(json("[{\"title\":\"A\",\"risk\":\"REVERSIBLE\"}]"))
                .withSubSteps(0, subs);
        assertThat(plan.steps().get(0).inProgress()).isTrue();
        assertThat(plan.steps().get(0).subStepsDone()).isEqualTo(1);
        assertThat(JourneyPlan.fromJson(plan.toJson())).isEqualTo(plan);
        assertThat(plan.withStep(0, JourneyPlan.StepStatus.FAIT, null).steps().get(0).inProgress()).isFalse();
        assertThat(plan.withStep(0, JourneyPlan.StepStatus.FAIT, null).steps().get(0).subSteps()).hasSize(2);
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 13; i++) {
            many.append(i == 0 ? "" : ",").append("{\"title\":\"s").append(i).append("\",\"done\":false}");
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JourneyPlan.subStepsFromToolInput(
                json(many.append("]").toString()))).hasMessageContaining("12 sous-étapes au plus");
        String legacy = "[{\"title\":\"A\",\"risk\":\"LECTURE\",\"verify\":null,\"rollback\":null,"
                + "\"waitsOn\":null,\"status\":\"FAIT\",\"evidence\":null}]";
        assertThat(JourneyPlan.fromJson(legacy).toJson()).isEqualTo(legacy);
    }

    @Test
    @DisplayName("SF-208-01 : un amendement garde dépendances et sous-étapes d'une étape au même titre et rang")
    void amendmentCarriesStructure() throws Exception {
        JourneyPlan v1 = JourneyPlan.fromToolInput(json("""
                [{"title":"A","risk":"LECTURE"},{"title":"B","risk":"REVERSIBLE","depends_on":[1]}]"""))
                .withStep(1, JourneyPlan.StepStatus.FAIT, null)
                .withSubSteps(1, java.util.List.of(new JourneyPlan.SubStep("b1", true, "ok")));
        JourneyPlan v2 = JourneyPlan.fromToolInput(json("""
                [{"title":"A","risk":"LECTURE"},{"title":"B","risk":"REVERSIBLE"},{"title":"C","risk":"NOTES"}]"""));
        JourneyPlan carried = SubjectJourneyService.carryProgress(v2, v1);
        assertThat(carried.steps().get(1).dependsOn()).containsExactly(1);
        assertThat(carried.steps().get(1).subSteps()).hasSize(1);
        assertThat(carried.steps().get(1).status()).isEqualTo(JourneyPlan.StepStatus.FAIT);
        assertThat(carried.changedSince(v1, 1)).isFalse();
    }

    @Test
    @DisplayName("SF-208-01 : la note de tour dit « en cours », « après 1 » et « sous-étapes 1/2 »")
    void turnNoteShowsStructure() throws Exception {
        JourneyPlan plan = JourneyPlan.fromToolInput(json("""
                [{"title":"Lire","risk":"LECTURE"},{"title":"Migrer","risk":"REVERSIBLE","depends_on":[1]}]"""))
                .withSubSteps(1, java.util.List.of(new JourneyPlan.SubStep("dump", true, "ok"),
                        new JourneyPlan.SubStep("restore", false, null)));
        SubjectJourney journey = SubjectJourney.builder().userId(UUID.randomUUID()).workspaceId(UUID.randomUUID())
                .mode(JourneyMode.GUIDE).phase(JourneyPhase.EXECUTION).planJson(plan.toJson()).planVersion(1)
                .validatedVersion(1).build();
        assertThat(JourneyTurnNote.render(journey))
                .contains("2. [en cours] Migrer · modification réversible · après 1 · sous-étapes 1/2");
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

    @Test
    @DisplayName("SF-176-05 : le diagnostic et la clôture proposée sont rappelés à l'agent")
    void turnNoteShowsDiagnosis() {
        SubjectJourney journey = SubjectJourney.builder().userId(UUID.randomUUID()).workspaceId(UUID.randomUUID())
                .mode(JourneyMode.GUIDE).phase(JourneyPhase.INVESTIGATION).diagnosis("Certificat expiré")
                .diagnosisConfidence("ELEVEE").diagnosisProposedAt(java.time.OffsetDateTime.now()).build();
        assertThat(JourneyTurnNote.render(journey)).contains("Diagnostic (confiance ELEVEE) : Certificat expiré")
                .contains("attend la confirmation");
        journey.setPhase(JourneyPhase.VERIFICATION);
        journey.setCloseProposedAt(java.time.OffsetDateTime.now());
        assertThat(JourneyTurnNote.render(journey)).contains("la clôture attend");
        assertThat(SubjectJourneyService.confidenceOf("élevée")).isEqualTo("ELEVEE");
        assertThat(SubjectJourneyService.confidenceOf("low")).isEqualTo("FAIBLE");
        assertThat(SubjectJourneyService.confidenceOf(null)).isEqualTo("MOYENNE");
    }

    @Test
    @DisplayName("SF-208-02 : ce qui bloque une étape (déclaré ou règle historique) et ce qui change d'une version à l'autre")
    void blockingAndChanges() throws Exception {
        JourneyPlan plan = JourneyPlan.fromToolInput(json("""
                [{"title":"A","risk":"LECTURE"},{"title":"B","risk":"LECTURE"},
                 {"title":"C","risk":"NOTES","depends_on":[1]},{"title":"D","risk":"NOTES"}]"""))
                .withStep(0, JourneyPlan.StepStatus.FAIT, null);
        assertThat(plan.blockingSteps(0)).isEmpty();
        assertThat(plan.blockingSteps(1)).isEmpty();
        assertThat(plan.blockingSteps(2)).isEmpty();
        assertThat(plan.blockingSteps(3)).containsExactly(3);
        assertThat(plan.pendingDeclared(3)).isEmpty();
        JourneyPlan next = JourneyPlan.fromToolInput(json("""
                [{"title":"A","risk":"LECTURE"},{"title":"C","risk":"EXTERNE"},{"title":"E","risk":"NOTES"}]"""));
        JourneyPlan.Changes changes = JourneyPlan.changes(plan, next);
        assertThat(changes.added()).containsExactly("E");
        assertThat(changes.removed()).containsExactly("B", "D");
        assertThat(changes.modified()).containsExactly("C");
        assertThat(JourneyPlan.changes(null, next).added()).containsExactly("A", "C", "E");
    }
}
