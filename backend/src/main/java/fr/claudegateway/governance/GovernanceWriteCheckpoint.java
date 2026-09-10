package fr.claudegateway.governance;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpoint;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;

/**
 * Le crochet d'<b>écriture</b> de F-50, branché sur les paquets actifs (F-51 / SF-51-04).
 *
 * <p>Après chaque écriture de fichier aboutie, les contrôles cités par les paquets actifs sur ce
 * projet sont interrogés. Le premier qui bloque transforme le résultat d'outil en <b>erreur</b>
 * portant l'action corrective : le fichier est écrit, et le modèle est tenu d'y revenir.</p>
 *
 * <p>La logique vit dans {@link GovernanceCheckpointDelegate} : F-50 attend un bean par point
 * d'accroche, pas deux logiques.</p>
 */
@Component
public class GovernanceWriteCheckpoint implements AtelierCheckpoint {

    private final GovernanceCheckpointDelegate delegate;

    public GovernanceWriteCheckpoint(GovernanceCheckpointDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public AtelierCheckpointKind kind() {
        return AtelierCheckpointKind.AFTER_FILE_WRITE;
    }

    @Override
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
        return delegate.evaluate(AtelierCheckpointKind.AFTER_FILE_WRITE, context);
    }
}
