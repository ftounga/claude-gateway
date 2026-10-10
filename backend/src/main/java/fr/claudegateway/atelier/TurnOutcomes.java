package fr.claudegateway.atelier;

import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.push.PushEvent;

/**
 * <b>Ce que dit la fin d'un tour</b> (F-185 / SF-185-02) : une seule notification par tour, la plus
 * importante. « Une réponse est prête » ne couvre plus un plan à approuver ni un poste perdu.
 */
final class TurnOutcomes {

    private TurnOutcomes() {
    }

    /**
     * L'événement à notifier, ou {@code null} quand l'utilisateur a lui-même interrompu le tour.
     * Ordre : interruption (rien), poste perdu, travail arrêté, plan, validation, délai écoulé,
     * réponse prête.
     */
    static PushEvent classify(AtelierChatResult result, TurnSignals signals) {
        if (result == null) {
            return PushEvent.WORK_STOPPED;
        }
        if (result.interrupted()) {
            // Le geste vient de l'utilisateur : il est là, rien à lui annoncer.
            return null;
        }
        if (result.stoppedByMachine() || (signals != null && signals.machineLost())) {
            return PushEvent.MACHINE_LOST;
        }
        if (result.budgetReached()) {
            return PushEvent.WORK_STOPPED;
        }
        if (result.planSubmitted()) {
            return PushEvent.PLAN_AWAITING;
        }
        if (signals != null && signals.validationAwaiting()) {
            return PushEvent.VALIDATION_AWAITING;
        }
        if (signals != null && signals.timedOut()) {
            return PushEvent.CONTINUED_WITHOUT_YOU;
        }
        return PushEvent.TURN_DONE;
    }
}
