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
import fr.claudegateway.governance.map.index.HostMapIndexProperties;
import fr.claudegateway.governance.map.index.HostMapLookup;
import fr.claudegateway.governance.map.index.HostMapLookupJournal;
import fr.claudegateway.governance.map.index.HostMapSearch;

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
    /** Le journal des consultations (F-174 / SF-174-01) : ce qui a été joint, compté. */
    private final HostMapLookupJournal journal;
    /** La recherche hybride sur l'index (F-174 / SF-174-03). */
    private final HostMapSearch search;
    private final HostMapIndexProperties indexProperties;

    public HostMapKnowledgeProvider(HostMapStore store, GovernanceHostScope hostScope,
            @Qualifier("hostMapRefreshExecutor") Executor executor, Clock clock,
            @Value("${app.governance.map.fact-max-age-days:120}") int factMaxAgeDays,
            HostMapLookupJournal journal, HostMapSearch search, HostMapIndexProperties indexProperties) {
        this.store = store;
        this.hostScope = hostScope;
        this.executor = executor;
        this.clock = clock;
        this.factMaxAgeDays = factMaxAgeDays;
        this.journal = journal;
        this.search = search;
        this.indexProperties = indexProperties;
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
        if (host == null || host.hostId() == null || host.hosted() || question == null
                || question.isBlank()) {
            return null; // « Hébergé » n'a pas de carte : rien à joindre, rien à mesurer.
        }
        LocalDate today = LocalDate.now(clock);
        // F-174 / SF-174-03 : d'abord l'index (recherche hybride), s'il est allumé et nourri pour ce
        // poste. Tout échec, ou une recherche vide, retombe sur le rappel F-137 d'avant, à l'identique.
        String hybrid = hybridFacts(userId, host.hostId(), question, today);
        if (hybrid != null) {
            journal.record(userId, host.hostId(), workspaceId, HostMapLookup.KIND_TURN,
                    HostMapLookup.STRATEGY_HYBRID, hybrid);
            return hybrid;
        }
        try {
            // La recherche porte sur la carte DU POSTE DU TOUR, lue par (user_id, host_id) : aucun
            // fait d'un autre client ne peut être joint à cette question.
            String block = HostFactLookup.factsFor(store.filesOf(userId, host.hostId()), question,
                    today, factMaxAgeDays);
            // F-174 / SF-174-01 : la mesure de départ. Chaque tour d'un poste à carte est compté,
            // y compris quand rien n'est joint — c'est aussi ce qu'on veut voir baisser.
            journal.record(userId, host.hostId(), workspaceId, HostMapLookup.KIND_TURN,
                    HostMapLookup.STRATEGY_LEXICAL, block);
            return block;
        } catch (RuntimeException ex) {
            log.debug("Rappel de faits indisponible ({})", ex.getClass().getSimpleName());
            return null;
        }
    }

    /** Le bloc de la recherche hybride, ou {@code null} pour retomber sur F-137. Ne lève jamais. */
    private String hybridFacts(UUID userId, UUID hostId, String question, LocalDate today) {
        if (search == null || indexProperties == null || !indexProperties.isEnabled()) {
            return null;
        }
        try {
            if (!search.hasIndex(userId, hostId)) {
                return null;
            }
            HostMapSearch.Result result = search.search(userId, hostId, question,
                    indexProperties.maxFacts());
            if (result.isEmpty()) {
                return null;
            }
            return HostMapFactsBlock.render(result.hits(), today, factMaxAgeDays,
                    indexProperties.maxChars());
        } catch (RuntimeException ex) {
            log.debug("Recherche hybride indisponible, repli lexical ({})", ex.getClass().getSimpleName());
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
