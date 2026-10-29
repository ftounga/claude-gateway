package fr.claudegateway.atelier.autonomy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyChantier;
import fr.claudegateway.atelier.journey.SubjectJourneyService;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.live.RemoteTurnSource;

/**
 * <b>Les fils actifs d'un sujet</b> (F-221 / SF-221-02) : le fil commun et chaque chantier qui ont un tour
 * vivant (ici ou chez un pair) ou une course autonome en cours. C'est ce que l'écran lit en ouvrant un sujet
 * pour décider du split : deux chantiers actifs ⇒ deux volets.
 *
 * <p>Isolation : {@code requireOwned} d'abord (404 sur le sujet d'autrui) ; le registre est clefé par
 * utilisateur, les courses filtrées par {@code user_id}.</p>
 */
@Service
public class ChantierLanesService {

    private final WorkspaceService workspaceService;
    private final LiveTurnRegistry liveTurns;
    private final RemoteTurnSource remoteTurns;
    private final AutonomyRunStore runs;
    private final SubjectJourneyService journeys;

    public ChantierLanesService(WorkspaceService workspaceService, LiveTurnRegistry liveTurns,
            RemoteTurnSource remoteTurns, AutonomyRunStore runs, SubjectJourneyService journeys) {
        this.workspaceService = workspaceService;
        this.liveTurns = liveTurns;
        this.remoteTurns = remoteTurns;
        this.runs = runs;
        this.journeys = journeys;
    }

    /**
     * Un fil actif.
     *
     * @param chantier  le chantier, {@code null} = fil commun
     * @param live      un tour vivant dans ce fil (ici ou chez un pair)
     * @param autonomy  une course autonome en cours sur ce chantier
     * @param startedAt début de l'activité la plus ancienne connue (ms epoch), pour l'ordre des volets
     */
    public record Lane(Integer chantier, boolean live, boolean autonomy, long startedAt) {
    }

    /** La réponse : les fils actifs, du plus ancien au plus récent. */
    public record Lanes(List<Lane> lanes) {
    }

    public Lanes lanes(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        Map<Integer, Lane> byLane = new LinkedHashMap<>();
        for (LiveTurn turn : liveTurns.liveOf(userId, workspaceId)) {
            merge(byLane, new Lane(turn.chantier(), true, false, turn.startedAtMs()));
        }
        for (ChantierAutonomyRun run : runs.runningOf(userId, workspaceId)) {
            long started = run.getStartedAt() == null ? 0L : run.getStartedAt().toInstant().toEpochMilli();
            merge(byLane, new Lane(run.getChantierNumber(), false, true, started));
        }
        // Un tour vivant chez un pair : sondé pour le fil commun et chaque chantier ouvert (quelques appels,
        // aucun sur un pod unique — le relais rend vide).
        List<Integer> probes = new ArrayList<>();
        probes.add(null);
        SubjectJourney journey = journeys.forTurn(userId, workspaceId);
        if (journey != null && journey.hasOpenFocus()) {
            probes.add(journey.getChantierNumber());
        }
        if (journey != null && journey.getOtherOpenChantiers() != null) {
            for (SubjectJourneyChantier other : journey.getOtherOpenChantiers()) {
                probes.add(other.getNumber());
            }
        }
        for (Integer lane : probes) {
            if (byLane.containsKey(key(lane)) && byLane.get(key(lane)).live()) {
                continue;
            }
            remoteTurns.findRemoteTurn(userId, workspaceId, lane)
                    .ifPresent(state -> merge(byLane, new Lane(lane, true, false, state.startedAtMs())));
        }
        List<Lane> sorted = new ArrayList<>(byLane.values());
        sorted.sort(Comparator.comparingLong(Lane::startedAt));
        return new Lanes(List.copyOf(sorted));
    }

    private static void merge(Map<Integer, Lane> byLane, Lane lane) {
        byLane.merge(key(lane.chantier()), lane, (a, b) -> new Lane(a.chantier(), a.live() || b.live(),
                a.autonomy() || b.autonomy(), Math.min(nonZero(a.startedAt()), nonZero(b.startedAt()))));
    }

    private static long nonZero(long value) {
        return value <= 0 ? Long.MAX_VALUE : value;
    }

    /** Le fil commun a sa clef : un numéro de chantier est toujours ≥ 1. */
    private static Integer key(Integer chantier) {
        return chantier == null ? 0 : chantier;
    }
}
