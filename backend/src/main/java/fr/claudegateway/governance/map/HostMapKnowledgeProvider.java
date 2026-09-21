package fr.claudegateway.governance.map;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.HostKnowledgeSource;
import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.GovernanceHostScope;

/**
 * Ce que la boucle sait du client, et quand le relire (F-136 / SF-136-01, SF-136-02).
 *
 * <p>Pont entre la boucle — qui ne connaît qu'un projet — et le magasin, qui raisonne par
 * <b>poste</b>. La traduction se fait ici, et nulle part ailleurs.</p>
 *
 * <p><b>Le rafraîchissement ne bloque jamais.</b> Il part dans un pool dédié une fois la réponse
 * envoyée : un savoir un peu ancien est acceptable, une seconde d'attente sur chaque demande ne
 * l'est pas. Une file pleine est ignorée en silence — le prochain tour relira.</p>
 */
@Component
public class HostMapKnowledgeProvider implements HostKnowledgeSource {

    private static final Logger log = LoggerFactory.getLogger(HostMapKnowledgeProvider.class);

    private final HostMapStore store;
    private final GovernanceHostScope hostScope;
    private final Executor executor;
    private final Clock clock;
    /**
     * Âge au-delà duquel un fait d'infrastructure est dit « à re-vérifier » (F-139 / SF-139-01).
     *
     * <p>Quatre mois par défaut : au-delà, une version, un certificat ou un droit ont eu le temps de
     * changer sans que personne ne l'écrive. Réglable, parce que le bon seuil dépend du client.</p>
     */
    private final int factMaxAgeDays;

    public HostMapKnowledgeProvider(HostMapStore store, GovernanceHostScope hostScope,
            @Qualifier("hostMapRefreshExecutor") Executor executor, Clock clock,
            @Value("${app.governance.map.fact-max-age-days:120}") int factMaxAgeDays) {
        this.store = store;
        this.hostScope = hostScope;
        this.executor = executor;
        this.clock = clock;
        this.factMaxAgeDays = factMaxAgeDays;
    }

    @Override
    public String outlineFor(UUID userId, UUID workspaceId) {
        GovernanceHostRef host = hostOf(userId, workspaceId);
        if (host == null || host.hostId() == null) {
            return null;
        }
        try {
            return HostMapOutline.of(store.filesOf(userId, host.hostId()));
        } catch (RuntimeException ex) {
            // Un magasin en panne coûte un savoir absent, jamais un tour raté.
            log.debug("Sommaire de carte indisponible ({})", ex.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public String factsFor(UUID userId, UUID workspaceId, String question) {
        GovernanceHostRef host = hostOf(userId, workspaceId);
        if (host == null || host.hostId() == null || question == null || question.isBlank()) {
            return null;
        }
        try {
            // La recherche porte sur la carte DU POSTE DU TOUR, lue par (user_id, host_id) : aucun
            // fait d'un autre client ne peut être joint à cette question.
            return HostFactLookup.factsFor(store.filesOf(userId, host.hostId()), question,
                    LocalDate.now(clock), factMaxAgeDays);
        } catch (RuntimeException ex) {
            log.debug("Rappel de faits indisponible ({})", ex.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public void refreshAfterTurn(UUID userId, UUID workspaceId) {
        GovernanceHostRef host = hostOf(userId, workspaceId);
        if (host == null || host.hostId() == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    store.refresh(userId, host);
                } catch (RuntimeException ex) {
                    log.debug("Carte non rafraîchie après le tour ({})",
                            ex.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException ex) {
            // File pleine, pool arrêté : le prochain tour relira. Rien à signaler à l'utilisateur.
            log.debug("Rafraîchissement de carte non planifié ({})", ex.getClass().getSimpleName());
        }
    }

    private GovernanceHostRef hostOf(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return null;
        }
        try {
            return hostScope.hostOf(userId, workspaceId);
        } catch (RuntimeException ex) {
            return null; // Projet sans poste, ou disparu : il n'y a pas de carte.
        }
    }
}
