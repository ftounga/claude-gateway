package fr.claudegateway.runner.relay;

import java.util.UUID;

/**
 * Enveloppes des gestes <b>diffusés</b> entre pods (F-38 / SF-38-13, contrat du relais §4 à §6) :
 * annuler, trancher une autorisation, interrompre un tour, marquer une session interrompue.
 *
 * <p>Le {@code userId} qu'elles transportent n'est <b>jamais</b> une authentification — celle-ci est
 * le secret partagé, vérifié en amont. Il sert de critère d'appartenance rejoué par
 * {@code RunnerConfirmationGate.resolve}, qui compare {@code userId} <i>et</i> {@code workspaceId} à
 * ceux de la demande en attente : un identifiant de corrélation deviné n'autorise rien chez
 * autrui.</p>
 */
final class RelayGestureRequests {

    private RelayGestureRequests() {
    }

    /** Annulation des appels en vol d'un workspace (contrat §4). */
    record CancelRequest(UUID workspaceId, String reason) {

        boolean isValid() {
            return workspaceId != null;
        }

        String safeReason() {
            return reason == null || reason.isBlank() ? "user_interrupt" : reason.trim();
        }
    }

    /** Décision de la porte de confirmation (contrat §5). */
    record ConfirmRequest(UUID userId, UUID workspaceId, String callId, Boolean allow,
            String reason) {

        boolean isValid() {
            return userId != null && workspaceId != null && callId != null && !callId.isBlank()
                    && allow != null;
        }
    }

    /** Interruption d'un tour d'atelier (contrat §6, clef {@code userId:workspaceId}). */
    record InterruptRequest(UUID userId, UUID workspaceId, String reason) {

        boolean isValid() {
            return userId != null && workspaceId != null;
        }

        String safeReason() {
            return reason == null || reason.isBlank() ? "user_interrupt" : reason.trim();
        }
    }

    /** Marque d'interruption d'une session Managed Agent (contrat §6, clef {@code sessionId}). */
    /**
     * Précision déposée pendant un tour (F-39 / SF-39-19), relayée au pod qui exécute la boucle.
     * Ce n'est pas une interruption : on n'arrête rien, on ajoute au contexte.
     */
    record SteerRequest(UUID userId, UUID workspaceId, String message) {

        boolean isValid() {
            return userId != null && workspaceId != null && message != null && !message.isBlank();
        }
    }

    record SessionInterruptRequest(String sessionId, Boolean mark) {

        boolean isValid() {
            return sessionId != null && !sessionId.isBlank() && mark != null;
        }
    }
}
