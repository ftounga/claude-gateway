package fr.claudegateway.atelier.fil;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.live.RemoteTurnSource;
import fr.claudegateway.atelier.live.TurnSummary;

/**
 * <b>Les tours du poste</b> (F-191 / SF-191-01, le fil des tâches) : ce qui tourne — ou vient de finir —
 * sur tous les terminaux du poste du terminal courant.
 *
 * <p><b>Lecture seule</b> : les résumés sont tenus par les tours eux-mêmes ({@code TurnDigest}), sur ce
 * pod et chez les pairs. Aucun appel modèle, aucune écriture.</p>
 *
 * <p><b>Isolation</b> : le poste vient de l'entité <b>possédée</b> ({@code requireOwned}) ; les résumés
 * sont lus par {@code userId} à la source ; seuls les terminaux du poste de cet utilisateur sont
 * gardés. Un tour d'un autre poste — ou d'autrui — n'apparaît jamais.</p>
 */
@Service
public class PosteTurnsService {

    static final int MAX_TURNS = 20;

    private final WorkspaceService workspaceService;
    private final WorkspaceRepository workspaces;
    private final LiveTurnRegistry liveTurns;
    private final RemoteTurnSource remoteTurns;
    private final Clock clock;

    public PosteTurnsService(WorkspaceService workspaceService, WorkspaceRepository workspaces,
            LiveTurnRegistry liveTurns, RemoteTurnSource remoteTurns, Clock clock) {
        this.workspaceService = workspaceService;
        this.workspaces = workspaces;
        this.liveTurns = liveTurns;
        this.remoteTurns = remoteTurns;
        this.clock = clock;
    }

    /**
     * Les tours du poste du terminal {@code workspaceId}.
     *
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException terminal d'autrui ou inexistant
     */
    @Transactional(readOnly = true)
    public PosteTurnsResponse turnsOfPoste(UUID userId, UUID workspaceId) {
        Workspace here = workspaceService.requireOwned(userId, workspaceId);
        UUID hostId = here.getHostId();
        List<Workspace> scope = hostId == null
                ? workspaces.findByUserIdAndHostIdIsNull(userId)
                : workspaces.findByUserIdAndHostId(userId, hostId);
        Map<UUID, Workspace> byId = new HashMap<>();
        for (Workspace w : scope) {
            // Défense en profondeur : la requête filtre déjà, l'entité le redit.
            if (userId.equals(w.getUserId()) && java.util.Objects.equals(hostId, w.getHostId())) {
                byId.put(w.getId(), w);
            }
        }
        byId.putIfAbsent(here.getId(), here);

        List<TurnSummary> all = new ArrayList<>(liveTurns.summariesOf(userId));
        all.addAll(remoteTurns.remoteSummariesOf(userId));

        // Un tour par identifiant (un vivant l'emporte sur son double), puis par terminal : un vivant
        // efface le fini précédent, et l'on ne garde que le fini le plus récent.
        Map<UUID, TurnSummary> byTurn = new LinkedHashMap<>();
        for (TurnSummary s : all) {
            if (s == null || s.turnId() == null || !userId.equals(s.userId())
                    || !byId.containsKey(s.workspaceId())) {
                continue;
            }
            byTurn.merge(s.turnId(), s, (a, b) -> a.live() ? a : b);
        }
        Map<UUID, TurnSummary> liveOf = new HashMap<>();
        Map<UUID, TurnSummary> finishedOf = new HashMap<>();
        for (TurnSummary s : byTurn.values()) {
            if (s.live()) {
                liveOf.merge(s.workspaceId(), s,
                        (a, b) -> a.startedAt() >= b.startedAt() ? a : b);
            } else {
                finishedOf.merge(s.workspaceId(), s,
                        (a, b) -> endOf(a) >= endOf(b) ? a : b);
            }
        }
        List<TurnSummary> kept = new ArrayList<>(liveOf.values());
        finishedOf.forEach((ws, s) -> {
            if (!liveOf.containsKey(ws)) {
                kept.add(s);
            }
        });
        kept.sort(ORDER);

        List<PosteTurnsResponse.Item> items = new ArrayList<>();
        for (TurnSummary s : kept) {
            if (items.size() >= MAX_TURNS) {
                break;
            }
            Workspace w = byId.get(s.workspaceId());
            items.add(new PosteTurnsResponse.Item(s.turnId(), s.workspaceId(), w.getName(),
                    w.isHostTerminal(), here.getId().equals(w.getId()), s.state(), s.waiting(), s.demand(),
                    s.startedAt(), s.endedAt(), s.planDone(), s.planTotal(), s.planStep(), s.tool(),
                    s.subagents()));
        }
        return new PosteTurnsResponse(hostId, clock.millis(), items);
    }

    /** Attend une décision → en cours (le plus ancien d'abord) → fini (le plus récent d'abord). */
    static final Comparator<TurnSummary> ORDER = Comparator
            .comparingInt((TurnSummary s) -> rank(s.state()))
            .thenComparing((a, b) -> a.live()
                    ? Long.compare(a.startedAt(), b.startedAt())
                    : Long.compare(endOf(b), endOf(a)));

    private static int rank(String state) {
        if (TurnSummary.WAITING.equals(state)) {
            return 0;
        }
        return TurnSummary.RUNNING.equals(state) ? 1 : 2;
    }

    private static long endOf(TurnSummary s) {
        return s.endedAt() == null ? 0L : s.endedAt();
    }
}
