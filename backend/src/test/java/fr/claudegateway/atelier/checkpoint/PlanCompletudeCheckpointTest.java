package fr.claudegateway.atelier.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.AtelierPlan;
import fr.claudegateway.atelier.AtelierPlan.Status;
import fr.claudegateway.atelier.AtelierPlan.Step;

/**
 * La porte de complétude (F-121 / SF-121-05) : elle refuse la fin d'un tour dont le plan porte encore
 * des étapes non terminées, et seulement dans ce cas — le tout sans le moindre appel au fournisseur
 * (aucune dépendance provider n'est injectée dans le contrôle).
 */
class PlanCompletudeCheckpointTest {

    private final PlanCompletudeCheckpoint gate = new PlanCompletudeCheckpoint(true);
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private AtelierCheckpointContext endOfTurnWith(AtelierPlan plan) {
        return AtelierCheckpointContext.endOfTurn(userId, null, workspaceId, "voilà.", List.of(),
                AtelierMachineReach.UNKNOWN, plan);
    }

    private static AtelierPlan planOf(Step... steps) {
        return new AtelierPlan(List.of(steps));
    }

    // -------------------------------------------------------------------- identité

    @Test
    @DisplayName("s'applique au crochet de fin de tour")
    void appliesToEndOfTurn() {
        assertThat(gate.kind()).isEqualTo(AtelierCheckpointKind.END_OF_TURN);
    }

    @Test
    @DisplayName("F-121 / SF-121-17 : juge aussi un tour qui n'a rien écrit — elle juge le PLAN")
    void judgesTurnsWithoutWrites() {
        // Sans cette déclaration, la neutralisation de SF-121-17 écarterait la porte de tout tour
        // d'investigation (lectures, bash) et SF-121-05 ne vaudrait plus que pour les tours qui
        // écrivent. Les contextes de ce test n'ont d'ailleurs aucun chemin écrit.
        assertThat(gate.judgesTurnWithoutWrites()).isTrue();
    }

    // -------------------------------------------------------------------- bloque

    @Test
    @DisplayName("une étape pending non terminée → bloque, et la correction cite l'étape")
    void blocksOnPending() {
        AtelierCheckpointVerdict verdict = gate.evaluate(endOfTurnWith(planOf(
                new Step("Écrire le service", Status.DONE),
                new Step("Écrire les tests", Status.PENDING))));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains("Écrire les tests");
        assertThat(verdict.correction()).doesNotContain("Écrire le service");
    }

    @Test
    @DisplayName("une étape active non terminée → bloque")
    void blocksOnActive() {
        AtelierCheckpointVerdict verdict = gate.evaluate(endOfTurnWith(planOf(
                new Step("Corriger le bug", Status.ACTIVE))));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains("Corriger le bug");
    }

    @Test
    @DisplayName("au-delà de la borne, la correction dit « et N autre(s) » sans tout transcrire")
    void citesBoundedNumberOfSteps() {
        Step[] steps = new Step[8];
        for (int i = 0; i < steps.length; i++) {
            steps[i] = new Step("Étape " + i, Status.PENDING);
        }
        AtelierCheckpointVerdict verdict = gate.evaluate(endOfTurnWith(planOf(steps)));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains("et 3 autre(s)");
    }

    // -------------------------------------------------------------------- laisse passer

    @Test
    @DisplayName("plan entièrement terminé → laisse passer")
    void proceedsWhenAllDone() {
        AtelierCheckpointVerdict verdict = gate.evaluate(endOfTurnWith(planOf(
                new Step("Écrire le service", Status.DONE),
                new Step("Écrire les tests", Status.DONE))));

        assertThat(verdict.blocked()).isFalse();
    }

    @Test
    @DisplayName("aucun plan posé → laisse passer (ne se déclenche que si un plan existe)")
    void proceedsWhenNoPlan() {
        assertThat(gate.evaluate(endOfTurnWith(AtelierPlan.EMPTY)).blocked()).isFalse();
    }

    @Test
    @DisplayName("plan null dans le contexte → traité comme vide, laisse passer")
    void proceedsWhenPlanNull() {
        // Le contexte normalise un plan null en EMPTY ; on le vérifie ici de bout en bout.
        AtelierCheckpointContext context = AtelierCheckpointContext.endOfTurn(userId, null,
                workspaceId, "voilà.", List.of(), AtelierMachineReach.UNKNOWN, null);
        assertThat(context.plan()).isEqualTo(AtelierPlan.EMPTY);
        assertThat(gate.evaluate(context).blocked()).isFalse();
    }

    // -------------------------------------------------------------------- coupe-circuit

    @Test
    @DisplayName("coupe-circuit désactivé → laisse toujours passer même avec une étape pending")
    void proceedsWhenDisabled() {
        PlanCompletudeCheckpoint off = new PlanCompletudeCheckpoint(false);
        assertThat(off.evaluate(endOfTurnWith(planOf(
                new Step("Reste à faire", Status.PENDING)))).blocked()).isFalse();
    }

    @Test
    @DisplayName("contexte null → laisse passer, jamais d'exception")
    void proceedsOnNullContext() {
        assertThat(gate.evaluate(null).blocked()).isFalse();
    }
}
