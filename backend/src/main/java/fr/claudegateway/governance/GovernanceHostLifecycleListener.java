package fr.claudegateway.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.runner.host.RunnerHostLifecycleEvent;

/**
 * Ce qu'un poste embarque à sa naissance, et ce qu'il emporte en disparaissant
 * (F-75 / SF-75-01).
 *
 * <p><b>À la création</b> : les paquets marqués « appliqué par défaut » sont activés sur le poste
 * neuf. C'est le nouveau point d'ancrage du drapeau — F-51 l'appliquait à chaque projet, ce qui
 * revenait à recommencer la gouvernance dossier par dossier. Un poste l'embarque une fois, et tous
 * ses dossiers en héritent, y compris ceux qu'on ajoutera demain.</p>
 *
 * <p><b>À la suppression</b> : les activations partent avec la machine. Une gouvernance qui
 * s'appliquerait à un poste effacé ne s'appliquerait à rien, et resterait comptée dans « actif sur
 * N postes ».</p>
 *
 * <p>L'écoute est <b>synchrone</b>, dans la transaction du poste : la naissance et la disparition
 * doivent être cohérentes avec leur gouvernance, et un poste supprimé ne doit jamais laisser
 * d'orphelin derrière lui. Rien ici ne remonte d'exception — la gouvernance ne doit pas faire échouer
 * la création d'un poste.</p>
 */
@Component
public class GovernanceHostLifecycleListener {

    private static final Logger log =
            LoggerFactory.getLogger(GovernanceHostLifecycleListener.class);

    private final GovernanceActivationService activationService;

    public GovernanceHostLifecycleListener(GovernanceActivationService activationService) {
        this.activationService = activationService;
    }

    @EventListener
    @Transactional
    public void onHostLifecycle(RunnerHostLifecycleEvent event) {
        try {
            switch (event.kind()) {
                case CREATED -> activationService.embarkDefaults(event.userId(),
                        GovernanceHostRef.of(event.hostId()));
                case DELETED -> activationService.forgetHost(event.userId(), event.hostId());
            }
        } catch (RuntimeException ex) {
            log.warn("Gouvernance du poste non traitée à son {} ({})", event.kind(),
                    ex.getClass().getSimpleName());
        }
    }
}
