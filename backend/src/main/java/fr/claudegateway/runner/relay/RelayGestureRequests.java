package fr.claudegateway.runner.relay;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Enveloppes des gestes <b>diffusés</b> entre pods (F-38 / SF-38-13, contrat du relais §4 à §6) :
 * annuler, trancher une autorisation, interrompre un tour, marquer une session interrompue.
 *
 * <p>Le {@code userId} qu'elles transportent n'est <b>jamais</b> une authentification — celle-ci est
 * le secret partagé, vérifié en amont. Il sert de critère d'appartenance rejoué par
 * {@code RunnerConfirmationGate.resolve}, qui compare {@code userId} <i>et</i> {@code workspaceId} à
 * ceux de la demande en attente : un identifiant de corrélation deviné n'autorise rien chez
 * autrui.</p>
 *
 * <p><b>Toutes tolérantes aux champs inconnus (F-81 / SF-81-02).</b> Ces enveloppes sont écrites par
 * un <b>autre pod</b> de la gateway. Pendant une bascule progressive, deux versions cohabitent et
 * le pod qui émet peut être plus récent que celui qui lit. Un champ ajouté ne doit pas transformer
 * un geste relayé en erreur : ce serait une annulation qui n'annule pas, ou une autorisation qui
 * n'arrive jamais, sur un poste en train de travailler.</p>
 */
final class RelayGestureRequests {

    private RelayGestureRequests() {
    }

    /** Annulation des appels en vol d'un workspace (contrat §4). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CancelRequest(UUID workspaceId, String reason) {

        boolean isValid() {
            return workspaceId != null;
        }

        String safeReason() {
            return reason == null || reason.isBlank() ? "user_interrupt" : reason.trim();
        }
    }

    /** Décision de la porte de confirmation (contrat §5). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConfirmRequest(UUID userId, UUID workspaceId, String callId, Boolean allow,
            String reason) {

        boolean isValid() {
            return userId != null && workspaceId != null && callId != null && !callId.isBlank()
                    && allow != null;
        }
    }

    /**
     * Désignation d'un <b>tour vivant</b> pour la sonde et le flux relayés (F-84 / SF-84-02).
     * Même règle que les autres enveloppes : le {@code userId} est un critère d'appartenance rejoué,
     * jamais une authentification.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TurnRequest(UUID userId, UUID workspaceId, Long cursor) {

        boolean isValid() {
            return userId != null && workspaceId != null;
        }

        /** Curseur exploitable : absent ou négatif vaut « je n'ai rien vu ». */
        long safeCursor() {
            return cursor == null || cursor < 0 ? 0L : cursor;
        }
    }

    /** Interruption d'un tour d'atelier (contrat §6, clef {@code userId:workspaceId}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
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
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SteerRequest(UUID userId, UUID workspaceId, String message) {

        boolean isValid() {
            return userId != null && workspaceId != null && message != null && !message.isBlank();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionInterruptRequest(String sessionId, Boolean mark) {

        boolean isValid() {
            return sessionId != null && !sessionId.isBlank() && mark != null;
        }
    }
}
