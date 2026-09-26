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
 *
 * <p><b>Il garde le défaut de SF-121-17</b> ({@code judgesTurnWithoutWrites() == false}) : les
 * contrôles qu'il délègue jugent tous le <b>travail écrit</b> — {@code juge-independant} audite les
 * fichiers écrits du tour, {@code integrite-du-poste} ne lance son inspection que si le tour a
 * écrit, {@code juge-fin-de-tour} et {@code promotion-dette-bloquante} sont retirés depuis
 * SF-125-06b. Sur un tour sans écriture, il n'est donc plus interrogé du tout : les paquets actifs
 * ne sont même pas résolus, et un tour de pure réponse n'est jamais renvoyé au travail.</p>
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
