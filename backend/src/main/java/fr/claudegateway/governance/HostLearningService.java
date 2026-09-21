package fr.claudegateway.governance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.governance.dto.HostLearningView;
import fr.claudegateway.runner.audit.RunnerAuditRepository;

/**
 * <b>L'application apprend-elle vraiment ?</b> (F-140 / SF-140-01)
 *
 * <p>Compte, par poste, combien d'appels d'outils il faut pour répondre à un tour — sur une fenêtre
 * récente et sur une longue. Si la carte sert, ce nombre <b>baisse</b> : l'agent cherche moins parce
 * qu'il sait déjà.</p>
 *
 * <p><b>Ce service ne juge pas.</b> Il rend les deux chiffres et laisse celui qui lit les comparer ;
 * une formule qui rendrait « ça s'améliore » masquerait ses hypothèses et finirait par mentir.</p>
 *
 * <p><b>La croissance de la carte n'est pas ici</b> : elle est déjà mesurée et affichée depuis
 * F-93 / SF-93-02. On ne la recalcule pas — deux chiffres pour le même fait finissent par
 * diverger.</p>
 */
@Service
public class HostLearningService {

    /** La fenêtre courte : assez pour voir un effet, assez peu pour qu'il soit récent. */
    static final int RECENT_DAYS = 7;

    /** La fenêtre longue : la référence à laquelle comparer. */
    static final int LONG_DAYS = 30;

    private final RunnerAuditRepository audits;
    private final AtelierMessageRepository messages;
    private final GovernanceHostScope hostScope;
    private final GovernanceMapGrowthRepository growth;
    private final Clock clock;

    public HostLearningService(RunnerAuditRepository audits, AtelierMessageRepository messages,
            GovernanceHostScope hostScope, GovernanceMapGrowthRepository growth, Clock clock) {
        this.audits = audits;
        this.messages = messages;
        this.hostScope = hostScope;
        this.growth = growth;
        this.clock = clock;
    }

    /** La mesure pour ce poste, <b>déjà vérifié possédé</b> par l'appelant. */
    @Transactional(readOnly = true)
    public HostLearningView describe(UUID userId, GovernanceHostRef host) {
        UUID hostId = host.hostId();
        if (hostId == null) {
            // Poste « Hébergé » : pas de machine, donc aucun appel d'outil de poste à compter.
            return new HostLearningView(0L, 0L, null, 0L, 0L, null, 0);
        }
        List<UUID> workspaces = hostScope.projectsOf(userId, host).stream()
                .map(fr.claudegateway.atelier.Workspace::getId)
                .toList();
        OffsetDateTime now = OffsetDateTime.now(clock);

        long recentTurns = turnsSince(userId, workspaces, now.minusDays(RECENT_DAYS));
        long recentCalls = audits.countByUserIdAndHostIdAndCreatedAtGreaterThanEqual(
                userId, hostId, now.minusDays(RECENT_DAYS));
        long longTurns = turnsSince(userId, workspaces, now.minusDays(LONG_DAYS));
        long longCalls = audits.countByUserIdAndHostIdAndCreatedAtGreaterThanEqual(
                userId, hostId, now.minusDays(LONG_DAYS));

        return new HostLearningView(recentTurns, recentCalls, ratio(recentCalls, recentTurns),
                longTurns, longCalls, ratio(longCalls, longTurns), factsOf(userId, hostId));
    }

    private long turnsSince(UUID userId, List<UUID> workspaces, OffsetDateTime since) {
        if (workspaces.isEmpty()) {
            return 0L; // Aucun projet : aucun tour, et surtout aucune requête « IN () ».
        }
        return messages.countByUserIdAndWorkspaceIdInAndRoleAndCreatedAtGreaterThanEqual(
                userId, workspaces, "USER", since);
    }

    private int factsOf(UUID userId, UUID hostId) {
        return growth.findByUserIdAndHostId(userId, hostId).stream()
                .mapToInt(GovernanceMapGrowth::getFacts)
                .sum();
    }

    /** {@code null} sans tour : « 0,0 appel par tour » se lirait comme un succès éclatant. */
    private static Double ratio(long calls, long turns) {
        if (turns <= 0) {
            return null;
        }
        return BigDecimal.valueOf(calls)
                .divide(BigDecimal.valueOf(turns), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
