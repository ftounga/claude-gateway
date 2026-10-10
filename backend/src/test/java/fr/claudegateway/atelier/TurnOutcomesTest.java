package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierQuestionResolved;
import fr.claudegateway.atelier.actions.AttenteBlock;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.runner.door.RunnerStopSummary;

/** Ce que dit la fin d'un tour (F-185 / SF-185-02) : une notification, la plus importante. */
class TurnOutcomesTest {

    private static AtelierChatResult result(String reply, boolean budgetReached, boolean planSubmitted) {
        return new AtelierChatResult(reply, List.of(), UUID.randomUUID(), 0L, 0L, 0L, budgetReached, null, null,
                planSubmitted);
    }

    private static TurnSignals signals(boolean validation, boolean timedOut, boolean machineLost) {
        TurnSignals signals = new TurnSignals(null);
        if (validation) {
            signals.onAttente("t", new AttenteBlock(UUID.randomUUID(), UUID.randomUUID(), AttenteBlock.PROPOSED,
                    "NONE", "x", null, null, null, null, null, null, null));
        }
        if (timedOut) {
            signals.onQuestionResolved(new AtelierQuestionResolved("c", "timeout", List.of()));
        }
        if (machineLost) {
            signals.onRunnerOffline(UUID.randomUUID());
        }
        return signals;
    }

    @Test
    void unTourOrdinaireAnnonceUneReponsePrete() {
        assertThat(TurnOutcomes.classify(result("Fait.", false, false), signals(false, false, false)))
                .isEqualTo(PushEvent.TURN_DONE);
    }

    @Test
    void uneInterruptionNAnnonceRienMemeAvecUnPlanOuUnDelai() {
        assertThat(TurnOutcomes.classify(result(AtelierChatService.INTERRUPTED_REPLY, false, true),
                signals(true, true, false))).isNull();
    }

    @Test
    void lePostePerduPasseAvantTout() {
        assertThat(TurnOutcomes.classify(result(RunnerStopSummary.PREFIX + " Outils : bash.", true, true),
                signals(true, true, false))).isEqualTo(PushEvent.MACHINE_LOST);
        assertThat(TurnOutcomes.classify(result("Le poste est hors ligne.", false, false),
                signals(false, false, true))).isEqualTo(PushEvent.MACHINE_LOST);
    }

    @Test
    void lePlafondAtteintArreteLeTravail() {
        assertThat(TurnOutcomes.classify(result("Plafond.", true, true), signals(true, true, false)))
                .isEqualTo(PushEvent.WORK_STOPPED);
    }

    @Test
    void unPlanSoumisPasseAvantLaValidation() {
        assertThat(TurnOutcomes.classify(result("Plan.", false, true), signals(true, true, false)))
                .isEqualTo(PushEvent.PLAN_AWAITING);
    }

    @Test
    void uneValidationPasseAvantLeDelaiEcoule() {
        assertThat(TurnOutcomes.classify(result("À confirmer.", false, false), signals(true, true, false)))
                .isEqualTo(PushEvent.VALIDATION_AWAITING);
    }

    @Test
    void unDelaiEcouleDitQueLAgentAContinueSansVous() {
        assertThat(TurnOutcomes.classify(result("Décidé par défaut.", false, false), signals(false, true, false)))
                .isEqualTo(PushEvent.CONTINUED_WITHOUT_YOU);
    }

    @Test
    void sansResultatLeTravailSEstArrete() {
        assertThat(TurnOutcomes.classify(null, null)).isEqualTo(PushEvent.WORK_STOPPED);
    }
}
