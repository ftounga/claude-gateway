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
 * Ce qu'un projet neuf embarque, sans que personne n'ait rien coché (F-51 / SF-51-03).
 *
 * <p>C'est la promesse du drapeau « appliqué par défaut » : les paquets ainsi marqués sont activés
 * sur chaque projet à venir de leur propriétaire, et leurs fichiers y sont déposés dans la foulée.
 * Les règles et les contrôles, eux, s'appliquent dès l'activation — ils n'ont besoin d'aucun
 * disque.</p>
 *
 * <p><b>Après validation, et sans jamais faire échouer la création.</b> Un projet doit se créer même
 * si la gouvernance a un hoquet : rien ici ne remonte d'exception, et l'écoute est placée
 * {@code AFTER_COMMIT} pour qu'aucun dépôt ne puisse annuler l'enregistrement du projet.</p>
 *
 * <p>Une machine éteinte n'empêche rien : les activations sont créées, le dépôt reste en attente et
 * l'écran offre « appliquer ».</p>
 */
@Component
public class GovernanceWorkspaceCreatedListener {

    private static final Logger log =
            LoggerFactory.getLogger(GovernanceWorkspaceCreatedListener.class);

    private final GovernanceActivationService activationService;
    private final GovernanceDepositService depositService;

    public GovernanceWorkspaceCreatedListener(GovernanceActivationService activationService,
            GovernanceDepositService depositService) {
        this.activationService = activationService;
        this.depositService = depositService;
    }

    /**
     * <b>{@code REQUIRES_NEW} n'est pas décoratif.</b> Après validation, la transaction d'origine est
     * close : sans transaction neuve, tout ce qui s'écrit ici part dans le vide — sans erreur, sans
     * trace, et la gouvernance par défaut ne s'appliquerait jamais qu'en apparence.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void embarkDefaults(WorkspaceCreatedEvent event) {
        try {
            if (activationService.embarkDefaults(event.userId(), event.workspaceId()).isEmpty()) {
                return; // Aucun paquet par défaut : rien à faire, et surtout rien à lire.
            }
            depositService.depositAllQuietly(event.userId(), event.workspaceId());
        } catch (RuntimeException ex) {
            // Le projet est déjà créé et validé : l'échec de la gouvernance ne doit pas remonter à
            // l'utilisateur comme un échec de création.
            log.warn("Gouvernance par défaut non embarquée sur un projet neuf ({})",
                    ex.getClass().getSimpleName());
        }
    }
}
