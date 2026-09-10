package fr.claudegateway.governance;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpoint;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;

/**
 * Le crochet <b>avant commande</b> (F-52 / SF-52-01), branché sur les paquets actifs.
 *
 * <p>Avant qu'une commande ne parte sur la machine de l'utilisateur, les contrôles cités par les
 * paquets actifs sur ce projet sont interrogés. Le premier qui bloque empêche l'émission : le
 * résultat d'outil devient une <b>erreur</b> portant l'action corrective, et la commande n'a jamais
 * tourné.</p>
 *
 * <p>La logique vit dans {@link GovernanceCheckpointDelegate} : F-50 attend un bean par point
 * d'accroche, pas trois logiques.</p>
 */
@Component
public class GovernanceCommandCheckpoint implements AtelierCheckpoint {

    private final GovernanceCheckpointDelegate delegate;

    public GovernanceCommandCheckpoint(GovernanceCheckpointDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public AtelierCheckpointKind kind() {
        return AtelierCheckpointKind.BEFORE_COMMAND;
    }

    @Override
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
        return delegate.evaluate(AtelierCheckpointKind.BEFORE_COMMAND, context);
    }
}
