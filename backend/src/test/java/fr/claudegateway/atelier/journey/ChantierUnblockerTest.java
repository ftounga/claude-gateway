package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.journey.ChantierUnblocker.Mark;
import fr.claudegateway.atelier.journey.ChantierUnblocker.Who;

/** F-220 / SF-220-01 — « Qui débloque ? », calcul pur : étapes puis chantier. */
class ChantierUnblockerTest {

    private static final OffsetDateTime ASKED = OffsetDateTime.of(2026, 10, 20, 9, 0, 0, 0, ZoneOffset.UTC);

    private static JourneyPlan plan(String json) {
        try {
            return JourneyPlan.fromToolInput(new ObjectMapper().readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JourneyPlan withStatus(JourneyPlan plan, int rank, JourneyPlan.StepStatus status, String evidence) {
        return plan.withStep(rank - 1, status, evidence);
    }

    private static Mark chantier(JourneyPhase phase, JourneyPlan plan, Integer validated, Map<String, WaitInfo> waits) {
        return ChantierUnblocker.chantier(new ChantierUnblocker.Input(true, phase, plan.toJson(), 1, validated,
                false, false, false, waits));
    }

    private static final String THREE = "[{\"title\":\"Lire les logs\",\"risk\":\"LECTURE\"},"
            + "{\"title\":\"Obtenir le certificat\",\"risk\":\"EXTERNE\",\"waits_on\":\"cert-gino\"},"
            + "{\"title\":\"Remplacer le certificat\",\"risk\":\"EXTERNE\"}]";

    @Test
    @DisplayName("étape exécutable : l'outil ; chantier en exécution : l'outil, prochaine étape 1")
    void toolCanAdvance() {
        JourneyPlan p = plan(THREE);
        List<Mark> marks = ChantierUnblocker.steps(p, Map.of("cert-gino", WaitInfo.ofStatus("FAIT")));
        assertThat(marks.get(0).who()).isEqualTo(Who.OUTIL);
        Mark m = chantier(JourneyPhase.EXECUTION, p, 1, Map.of("cert-gino", WaitInfo.ofStatus("FAIT")));
        assertThat(m.who()).isEqualTo(Who.OUTIL);
        assertThat(m.step()).isEqualTo(1);
        assertThat(m.label()).isEqualTo("L'outil peut avancer");
    }

    @Test
    @DisplayName("attente ouverte d'un client : quelqu'un d'autre, à qui et depuis quand ; la suite hérite")
    void waitingOnSomeone() {
        JourneyPlan p = withStatus(plan(THREE), 1, JourneyPlan.StepStatus.VERIFIE, "ok");
        Map<String, WaitInfo> waits = Map.of("cert-gino", new WaitInfo("DEMANDE", "CLIENT", "Gino", ASKED));
        List<Mark> marks = ChantierUnblocker.steps(p, waits);
        assertThat(marks.get(0)).isNull();
        assertThat(marks.get(1).who()).isEqualTo(Who.AUTRUI);
        assertThat(marks.get(1).waitingOn()).isEqualTo("Gino");
        assertThat(marks.get(1).since()).isEqualTo(ASKED);
        assertThat(marks.get(1).detail()).contains("auprès de Gino").contains("demandé");
        assertThat(marks.get(2).who()).isEqualTo(Who.AUTRUI);
        assertThat(marks.get(2).step()).isEqualTo(2);
        assertThat(marks.get(2).detail()).startsWith("Étape 3 : attend l'étape 2");

        Mark m = chantier(JourneyPhase.EXECUTION, p, 1, waits);
        assertThat(m.who()).isEqualTo(Who.AUTRUI);
        assertThat(m.waitingOn()).isEqualTo("Gino");
    }

    @Test
    @DisplayName("une attente « à moi » est ma décision")
    void myOwnWait() {
        JourneyPlan p = withStatus(plan(THREE), 1, JourneyPlan.StepStatus.VERIFIE, "ok");
        Map<String, WaitInfo> waits = Map.of("cert-gino", new WaitInfo("A_FAIRE", "MOI", null, ASKED));
        assertThat(ChantierUnblocker.steps(p, waits).get(1).who()).isEqualTo(Who.MOI);
        assertThat(chantier(JourneyPhase.EXECUTION, p, 1, waits).who()).isEqualTo(Who.MOI);
    }

    @Test
    @DisplayName("une information que personne ne suit : quelqu'un d'autre")
    void missingInfo() {
        JourneyPlan p = withStatus(plan(THREE), 1, JourneyPlan.StepStatus.VERIFIE, "ok");
        Mark m = ChantierUnblocker.steps(p, Map.of()).get(1);
        assertThat(m.who()).isEqualTo(Who.AUTRUI);
        assertThat(m.detail()).contains("personne ne le suit");
    }

    @Test
    @DisplayName("étape bloquée par l'agent : ma décision, avec la raison")
    void blockedStep() {
        JourneyPlan p = withStatus(plan(THREE), 1, JourneyPlan.StepStatus.BLOQUE, "accès refusé au cluster");
        Mark step = ChantierUnblocker.steps(p, Map.of()).get(0);
        assertThat(step.who()).isEqualTo(Who.MOI);
        assertThat(step.detail()).contains("accès refusé au cluster");
        Mark m = chantier(JourneyPhase.EXECUTION, p, 1, Map.of());
        assertThat(m.who()).isEqualTo(Who.MOI);
        assertThat(m.step()).isEqualTo(1);
    }

    @Test
    @DisplayName("une étape bloquée et une autre exécutable (dépendances déclarées) : l'outil peut avancer")
    void mixedPrefersTool() {
        JourneyPlan p = plan("[{\"title\":\"A\",\"risk\":\"LECTURE\"},{\"title\":\"B\",\"risk\":\"LECTURE\"},"
                + "{\"title\":\"C\",\"risk\":\"LECTURE\",\"depends_on\":[1]}]");
        p = withStatus(p, 1, JourneyPlan.StepStatus.VERIFIE, "ok");
        p = withStatus(p, 2, JourneyPlan.StepStatus.BLOQUE, "manque un accès");
        List<Mark> marks = ChantierUnblocker.steps(p, Map.of());
        assertThat(marks.get(1).who()).isEqualTo(Who.MOI);
        assertThat(marks.get(2).who()).isEqualTo(Who.OUTIL);
        Mark m = chantier(JourneyPhase.EXECUTION, p, 1, Map.of());
        assertThat(m.who()).isEqualTo(Who.OUTIL);
        assertThat(m.step()).isEqualTo(3);
    }

    @Test
    @DisplayName("plan posé non validé, ou amendé : ma décision")
    void planToValidate() {
        JourneyPlan p = plan(THREE);
        assertThat(chantier(JourneyPhase.PLAN, p, null, Map.of()).who()).isEqualTo(Who.MOI);
        Mark amended = ChantierUnblocker.chantier(new ChantierUnblocker.Input(true, JourneyPhase.EXECUTION,
                p.toJson(), 2, 1, false, false, false, Map.of()));
        assertThat(amended.who()).isEqualTo(Who.MOI);
        assertThat(amended.detail()).contains("amendé");
    }

    @Test
    @DisplayName("question en suspens, clôture proposée, diagnostic en attente : ma décision")
    void decisions() {
        String json = plan(THREE).toJson();
        assertThat(ChantierUnblocker.chantier(new ChantierUnblocker.Input(true, JourneyPhase.EXECUTION, json, 1, 1,
                false, false, true, Map.of())).detail()).contains("question");
        assertThat(ChantierUnblocker.chantier(new ChantierUnblocker.Input(true, JourneyPhase.EXECUTION, json, 1, 1,
                false, true, false, Map.of())).who()).isEqualTo(Who.MOI);
        assertThat(ChantierUnblocker.chantier(new ChantierUnblocker.Input(true, JourneyPhase.INVESTIGATION, null, 0,
                null, true, false, false, Map.of())).who()).isEqualTo(Who.MOI);
    }

    @Test
    @DisplayName("investigation, plan à rédiger : l'outil ; tout vérifié : à clore (ma décision)")
    void phases() {
        assertThat(ChantierUnblocker.chantier(new ChantierUnblocker.Input(true, JourneyPhase.INVESTIGATION, null, 0,
                null, false, false, false, Map.of())).who()).isEqualTo(Who.OUTIL);
        assertThat(ChantierUnblocker.chantier(new ChantierUnblocker.Input(true, JourneyPhase.PLAN, null, 0,
                null, false, false, false, Map.of())).who()).isEqualTo(Who.OUTIL);
        JourneyPlan p = plan("[{\"title\":\"A\",\"risk\":\"LECTURE\"}]");
        p = withStatus(p, 1, JourneyPlan.StepStatus.VERIFIE, "ok");
        Mark done = chantier(JourneyPhase.VERIFICATION, p, 1, Map.of());
        assertThat(done.who()).isEqualTo(Who.MOI);
        assertThat(done.detail()).contains("à clore");
    }

    @Test
    @DisplayName("étape faite, pas vérifiée : l'outil la vérifie")
    void doneToVerify() {
        JourneyPlan p = plan("[{\"title\":\"A\",\"risk\":\"LECTURE\"}]");
        p = withStatus(p, 1, JourneyPlan.StepStatus.FAIT, null);
        assertThat(chantier(JourneyPhase.VERIFICATION, p, 1, Map.of()).who()).isEqualTo(Who.OUTIL);
    }

    @Test
    @DisplayName("une étape EXTERNE exécutable reste à l'outil (F-218 : l'irréversible part sans confirmation)")
    void externalIsTool() {
        JourneyPlan p = plan("[{\"title\":\"Pousser la MR\",\"risk\":\"EXTERNE\"}]");
        assertThat(ChantierUnblocker.steps(p, Map.of()).get(0).who()).isEqualTo(Who.OUTIL);
    }
}
