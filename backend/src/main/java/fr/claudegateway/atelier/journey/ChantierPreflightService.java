package fr.claudegateway.atelier.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;

/**
 * <b>Le contrôle avant départ des chantiers d'un sujet</b> (F-219 / SF-219-01) : lit le plan de chaque
 * chantier ouvert, l'état des attentes dont ses étapes dépendent et l'état du tour vivant, puis délègue le
 * calcul à {@link ChantierPreflight#evaluate}.
 *
 * <p><b>Isolation</b> : {@code requireOwned(userId, workspaceId)} d'abord (404 sur le terminal d'autrui) ;
 * le parcours et ses chantiers sont relus sous {@code (user_id, workspace_id)}.</p>
 */
@Service
public class ChantierPreflightService {

    private final SubjectJourneyService journeys;
    private final WorkspaceService workspaceService;
    private final LiveTurnRegistry liveTurns;

    public ChantierPreflightService(SubjectJourneyService journeys, WorkspaceService workspaceService,
            LiveTurnRegistry liveTurns) {
        this.journeys = journeys;
        this.workspaceService = workspaceService;
        this.liveTurns = liveTurns;
    }

    /** Un chantier ouvert et son préflight. */
    public record ChantierReadiness(int number, String title, ChantierPreflight preflight) {
    }

    /** Le préflight de chaque chantier ouvert du sujet, par numéro croissant. 404 sur un terminal d'autrui. */
    @Transactional(readOnly = true)
    public List<ChantierReadiness> all(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        return evaluateAll(userId, workspace, journeys.forTurn(userId, workspaceId));
    }

    /**
     * Le préflight du chantier ouvert {@code number}. 404 sur un terminal d'autrui.
     *
     * @throws InvalidJourneyException le chantier n'est pas ouvert
     */
    @Transactional(readOnly = true)
    public ChantierReadiness one(UUID userId, UUID workspaceId, int number) {
        return all(userId, workspaceId).stream()
                .filter(c -> c.number() == number)
                .findFirst()
                .orElseThrow(() -> new InvalidJourneyException("Le chantier " + number + " n'est pas ouvert."));
    }

    /**
     * Même calcul, pour un appelant qui a <b>déjà</b> vérifié la propriété du terminal (la boucle autonome,
     * l'outil de l'agent) — jamais exposé tel quel à une route.
     */
    @Transactional(readOnly = true)
    public List<ChantierReadiness> forOwned(UUID userId, Workspace workspace) {
        return evaluateAll(userId, workspace, journeys.forTurn(userId, workspace.getId()));
    }

    private List<ChantierReadiness> evaluateAll(UUID userId, Workspace workspace, SubjectJourney journey) {
        List<ChantierReadiness> result = new ArrayList<>();
        if (journey == null || journey.getCreatedAt() == null) {
            return result;
        }
        Map<String, WaitInfo> infos = journeys.waitInfos(userId, workspace, journey);
        Map<String, String> waits = new java.util.LinkedHashMap<>();
        infos.forEach((key, info) -> waits.put(key, info.status()));
        boolean questionPending = questionPending(userId, workspace.getId());
        boolean guided = journey.isGuided();
        if (journey.hasOpenFocus()) {
            int number = Math.max(1, journey.getChantierNumber());
            result.add(new ChantierReadiness(number, journey.getChantierTitle(), ChantierPreflight.evaluate(
                    new ChantierPreflight.Input(number, guided, journey.getPhase(), journey.getPlanJson(),
                            journey.getPlanVersion(), journey.getValidatedVersion(), waits, questionPending),
                    infos)));
        }
        for (SubjectJourneyChantier row : journey.getOtherOpenChantiers()) {
            result.add(new ChantierReadiness(row.getNumber(), row.getTitle(), ChantierPreflight.evaluate(
                    new ChantierPreflight.Input(row.getNumber(), guided, row.getPhase(), row.getPlanJson(),
                            row.getPlanVersion() == null ? 0 : row.getPlanVersion(), row.getValidatedVersion(),
                            waits, questionPending), infos)));
        }
        result.sort(java.util.Comparator.comparingInt(ChantierReadiness::number));
        return List.copyOf(result);
    }

    /** Une question ou une autorisation encore ouverte sur le tour vivant du sujet (F-164). */
    private boolean questionPending(UUID userId, UUID workspaceId) {
        return liveTurns.find(userId, workspaceId)
                .map(turn -> pending(turn))
                .orElse(false);
    }

    private static boolean pending(LiveTurn turn) {
        return turn.pendingQuestion().map(q -> q.stillOpen()).orElse(false)
                || turn.pendingApproval().map(a -> a.stillOpen()).orElse(false);
    }
}
