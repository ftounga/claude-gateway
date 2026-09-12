package fr.claudegateway.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import fr.claudegateway.atelier.WorkspaceCreatedEvent;

/**
 * Ce qu'un dossier neuf <b>hérite de son poste</b> (F-51 / SF-51-03, regrainé par F-75 / SF-75-01).
 *
 * <p>C'est la promesse même de F-75 : on active une fois sur un poste, et <b>tout dossier ajouté
 * demain sous cette racine en hérite</b> — sans que personne n'y pense, sans rien recocher. Le
 * dossier neuf ne crée <b>aucune</b> activation : il reçoit les fichiers des paquets déjà actifs sur
 * son poste. Les règles et les contrôles, eux, s'appliquent d'emblée — ils n'ont besoin d'aucun
 * disque.</p>
 *
 * <p>Un projet sans machine est rangé sous le poste « Hébergé » (F-71), et hérite de ce que ce poste
 * porte. C'est là que le drapeau « appliqué par défaut » retrouve son sens pour les projets hébergés :
 * il est embarqué sur le poste, une fois.</p>
 *
 * <p><b>Après validation, et sans jamais faire échouer la création.</b> Un projet doit se créer même
 * si la gouvernance a un hoquet : rien ici ne remonte d'exception, et l'écoute est placée
 * {@code AFTER_COMMIT} pour qu'aucun dépôt ne puisse annuler l'enregistrement du projet.</p>
 *
 * <p>Une machine éteinte n'empêche rien : l'activation du poste repasse en attente et l'écran offre
 * « appliquer ».</p>
 */
@Component
public class GovernanceWorkspaceCreatedListener {

    private static final Logger log =
            LoggerFactory.getLogger(GovernanceWorkspaceCreatedListener.class);

    private final GovernanceActivationService activationService;
    private final GovernanceDepositService depositService;
    private final GovernanceHostScope hostScope;

    public GovernanceWorkspaceCreatedListener(GovernanceActivationService activationService,
            GovernanceDepositService depositService, GovernanceHostScope hostScope) {
        this.activationService = activationService;
        this.depositService = depositService;
        this.hostScope = hostScope;
    }

    /**
     * <b>{@code REQUIRES_NEW} n'est pas décoratif.</b> Après validation, la transaction d'origine est
     * close : sans transaction neuve, tout ce qui s'écrit ici part dans le vide — sans erreur, sans
     * trace, et la gouvernance héritée ne s'appliquerait jamais qu'en apparence.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inheritFromHost(WorkspaceCreatedEvent event) {
        try {
            GovernanceHostRef host =
                    hostScope.hostOf(hostScope.projectOf(event.userId(), event.workspaceId()));
            if (host.hosted()) {
                // Le poste « Hébergé » n'a pas de geste de création : c'est le premier projet sans
                // machine qui le fait exister. C'est donc ici qu'il embarque la sélection par défaut.
                activationService.embarkDefaults(event.userId(), host);
            }
            if (activationService.activeOn(event.userId(), host).isEmpty()) {
                return; // Poste non gouverné : rien à faire, et surtout rien à lire.
            }
            depositService.depositOnNewProjectQuietly(event.userId(), event.workspaceId());
        } catch (RuntimeException ex) {
            // Le projet est déjà créé et validé : l'échec de la gouvernance ne doit pas remonter à
            // l'utilisateur comme un échec de création.
            log.warn("Gouvernance du poste non héritée par un projet neuf ({})",
                    ex.getClass().getSimpleName());
        }
    }
}
