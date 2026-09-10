package fr.claudegateway.governance;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpoint;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;

/**
 * Le crochet de <b>fin de tour</b> de F-50, branché sur les paquets actifs (F-51 / SF-51-04).
 *
 * <p>Quand le modèle croit avoir fini, les contrôles cités par les paquets actifs sur ce projet sont
 * interrogés. Le premier qui bloque <b>refuse la fin du tour</b> : la correction est déposée comme
 * message utilisateur, et la boucle repart — bornée, comme F-50 l'a fixé.</p>
 *
 * <p>La logique vit dans {@link GovernanceCheckpointDelegate} : F-50 attend un bean par point
 * d'accroche, pas deux logiques.</p>
 */
@Component
public class GovernanceEndOfTurnCheckpoint implements AtelierCheckpoint {

    private final GovernanceCheckpointDelegate delegate;

    public GovernanceEndOfTurnCheckpoint(GovernanceCheckpointDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public AtelierCheckpointKind kind() {
        return AtelierCheckpointKind.END_OF_TURN;
    }

    @Override
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
        return delegate.evaluate(AtelierCheckpointKind.END_OF_TURN, context);
    }
}
