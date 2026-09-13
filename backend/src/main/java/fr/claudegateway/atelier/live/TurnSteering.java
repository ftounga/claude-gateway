package fr.claudegateway.atelier.live;

import java.util.Optional;
import java.util.UUID;

/**
 * Dépose une <b>précision</b> dans le tour vivant d'un projet, où qu'il tourne (F-84 / SF-84-06) :
 * sur ce pod d'abord, sinon chez le pair qui le détient.
 *
 * <p>Isolation : le tour est cherché par {@code (userId, workspaceId)}, ici comme chez le pair —
 * une précision ne tombe jamais dans le tour d'autrui.</p>
 */
public final class TurnSteering {

    private final LiveTurnRegistry liveTurns;
    private final RemoteTurnSource remoteTurns;

    public TurnSteering(LiveTurnRegistry liveTurns, RemoteTurnSource remoteTurns) {
        this.liveTurns = liveTurns;
        this.remoteTurns = remoteTurns;
    }

    /**
     * @return le reçu du tour qui a pris (ou refusé, file pleine) la précision ; vide si aucun tour
     *         ne tourne plus sur ce projet — ni ici, ni chez un pair joignable
     */
    public Optional<SteerReceipt> steer(UUID userId, UUID workspaceId, String text) {
        Optional<LiveTurn> local = liveTurns.find(userId, workspaceId);
        if (local.isPresent()) {
            // Scellé ou fini (ENDED) : ce tour a rendu sa réponse et ne lira plus rien — c'est lui qui
            // tourne sur ce projet, inutile de sonder les pairs.
            SteerReceipt receipt = local.get().offerSteer(text);
            return receipt.status() == SteerReceipt.Status.ENDED ? Optional.empty() : Optional.of(receipt);
        }
        return remoteTurns.steerRemoteTurn(userId, workspaceId, text)
                .filter(receipt -> receipt.status() != SteerReceipt.Status.ENDED);
    }
}
