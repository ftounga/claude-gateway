package fr.claudegateway.atelier.dashboard;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.autonomy.AutonomyService;
import fr.claudegateway.atelier.journey.ChantierPreflight;
import fr.claudegateway.atelier.journey.ChantierPreflightService;
import fr.claudegateway.atelier.journey.ChantierPreflightService.ChantierReadiness;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyChantier;
import fr.claudegateway.atelier.journey.SubjectJourneyRepository;
import fr.claudegateway.atelier.journey.SubjectJourneyResponse;
import fr.claudegateway.atelier.journey.SubjectJourneyService;
import fr.claudegateway.atelier.journey.WaitInfo;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>Le tableau de bord d'un client</b> (F-220 / SF-220-02) : tous les chantiers ouverts de tous les sujets d'un
 * poste, en une lecture — qui les débloque (SF-220-01), depuis quand ils dorment, ce qui leur manque, leur course
 * autonome (F-219). Rien n'est calculé de neuf : ce sont des <b>lectures</b> de ce que les autres features rangent
 * déjà (parcours, attentes, préflight, courses, dernière activité).
 *
 * <p><b>Isolation</b> : {@code RunnerHostService.requireOwned(userId, hostId)} d'abord (404 sur le poste d'autrui) ;
 * les sujets sont ceux du poste <b>possédé</b> ({@code user_id} + {@code host_id}) ; chaque lecture porte
 * {@code user_id}. Le terminal Teams n'est pas un sujet de travail : il n'y figure pas.</p>
 */
@Service
public class ClientBoardService {

    private static final Logger log = LoggerFactory.getLogger(ClientBoardService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_QUESTION = 300;

    private final RunnerHostService hosts;
    private final WorkspaceService workspaces;
    private final SubjectJourneyRepository journeyRepository;
    private final SubjectJourneyService journeys;
    private final ChantierPreflightService preflights;
    private final AutonomyService autonomy;
    private final AtelierMessageRepository messages;
    private final LiveTurnRegistry liveTurns;
    private final Clock clock;
    private final int dormantAfterDays;

    public ClientBoardService(RunnerHostService hosts, WorkspaceService workspaces,
            SubjectJourneyRepository journeyRepository, SubjectJourneyService journeys,
            ChantierPreflightService preflights, AutonomyService autonomy, AtelierMessageRepository messages,
            LiveTurnRegistry liveTurns, Clock clock,
            @Value("${app.client-board.dormant-days:2}") int dormantAfterDays) {
        this.hosts = hosts;
        this.workspaces = workspaces;
        this.journeyRepository = journeyRepository;
        this.journeys = journeys;
        this.preflights = preflights;
        this.autonomy = autonomy;
        this.messages = messages;
        this.liveTurns = liveTurns;
        this.clock = clock;
        this.dormantAfterDays = Math.max(1, dormantAfterDays);
    }

    /** Le tableau du poste {@code hostId}. 404 sur le poste d'autrui. */
    @Transactional(readOnly = true)
    public ClientBoard board(UUID userId, UUID hostId) {
        RunnerHost host = hosts.requireOwned(userId, hostId); // 404 — TOUJOURS en premier
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<UUID, Workspace> subjects = subjects(userId, hostId);
        List<ClientBoard.Chantier> cards = new ArrayList<>();
        List<AutonomyService.RunView> runs = runs(userId);
        if (!subjects.isEmpty()) {
            Map<UUID, OffsetDateTime> lastMessage = lastMessages(userId, subjects);
            for (SubjectJourney row : journeyRepository.findByUserIdAndWorkspaceIdIn(userId, subjects.keySet())) {
                Workspace workspace = subjects.get(row.getWorkspaceId());
                if (workspace == null || !userId.equals(row.getUserId())) {
                    continue;
                }
                try {
                    cards.addAll(cardsOf(userId, workspace, journeys.withOpenChantiers(row),
                            lastMessage.get(workspace.getId()), runs, now));
                } catch (RuntimeException e) {
                    log.warn("Tableau du client : sujet illisible ({})", e.getClass().getSimpleName());
                }
            }
        }
        cards.sort(ORDER);
        return new ClientBoard(host.getId(), host.getName(), now, dormantAfterDays, counts(cards),
                new ClientBoard.Autonomy(runs.stream().filter(r -> "RUNNING".equals(r.status())).count(),
                        autonomy.maxParallel()),
                List.copyOf(cards));
    }

    /** Outil, puis Ma décision, puis Quelqu'un ; le plus dormant d'abord ; puis sujet et numéro (stable). */
    static final Comparator<ClientBoard.Chantier> ORDER = Comparator
            .comparingInt((ClientBoard.Chantier c) -> c.unblocker() == null ? 3 : c.unblocker().who().ordinal())
            .thenComparing(Comparator.comparingLong(ClientBoard.Chantier::idleDays).reversed())
            .thenComparing(c -> c.workspaceName() == null ? "" : c.workspaceName(), String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(ClientBoard.Chantier::number);

    /** Les sujets du poste possédé : ses projets et son terminal ; jamais le terminal Teams. */
    private Map<UUID, Workspace> subjects(UUID userId, UUID hostId) {
        Map<UUID, Workspace> subjects = new LinkedHashMap<>();
        List<Workspace> all = new ArrayList<>(workspaces.listByHost(userId, hostId));
        workspaces.findHostTerminal(userId, hostId).ifPresent(all::add);
        for (Workspace w : all) {
            if (userId.equals(w.getUserId()) && hostId.equals(w.getHostId()) && !w.isTeamsTerminal()) {
                subjects.put(w.getId(), w);
            }
        }
        return subjects;
    }

    private Map<UUID, OffsetDateTime> lastMessages(UUID userId, Map<UUID, Workspace> subjects) {
        Map<UUID, OffsetDateTime> lastAt = new HashMap<>();
        try {
            for (Object[] row : messages.lastActivityByWorkspace(subjects.keySet(), userId)) {
                if (row != null && row.length == 2 && row[0] instanceof UUID id && row[1] instanceof OffsetDateTime at) {
                    lastAt.put(id, at);
                }
            }
        } catch (RuntimeException e) {
            log.debug("Dernière activité illisible (best-effort) : {}", e.getMessage());
        }
        return lastAt;
    }

    private List<AutonomyService.RunView> runs(UUID userId) {
        try {
            return autonomy.runs(userId);
        } catch (RuntimeException e) {
            log.debug("Courses autonomes illisibles (best-effort) : {}", e.getMessage());
            return List.of();
        }
    }

    private List<ClientBoard.Chantier> cardsOf(UUID userId, Workspace workspace, SubjectJourney journey,
            OffsetDateTime lastMessage, List<AutonomyService.RunView> runs, OffsetDateTime now) {
        if (journey == null || journey.getCreatedAt() == null || !journey.isGuided()) {
            return List.of();
        }
        Optional<LiveTurn> turn = liveTurns.find(userId, workspace.getId());
        String question = turn.map(ClientBoardService::question).orElse(null);
        Map<String, WaitInfo> waits = journeys.waitInfos(userId, workspace, journey);
        SubjectJourneyResponse view = SubjectJourneyResponse.fromWaits(journey, waits, 0, question != null);
        if (view.chantiers().isEmpty()) {
            return List.of();
        }
        Map<Integer, ChantierPreflight> preflight = new HashMap<>();
        for (ChantierReadiness r : preflights.forOwned(userId, workspace)) {
            preflight.put(r.number(), r.preflight());
        }
        Map<Integer, OffsetDateTime> updatedAt = new HashMap<>();
        for (SubjectJourneyChantier other : journey.getOtherOpenChantiers()) {
            updatedAt.put(other.getNumber(), other.getUpdatedAt());
        }
        List<ClientBoard.Chantier> cards = new ArrayList<>();
        for (SubjectJourneyResponse.OpenChantier c : view.chantiers()) {
            OffsetDateTime last = c.focus() ? latest(journey.getUpdatedAt(), lastMessage) : updatedAt.get(c.number());
            last = latest(last, c.openedAt());
            long idle = last == null ? 0 : Math.max(0, Duration.between(last, now).toDays());
            AutonomyService.RunView run = runs.stream()
                    .filter(r -> workspace.getId().equals(r.workspaceId()) && r.chantierNumber() == c.number())
                    .findFirst().orElse(null);
            boolean running = run != null && "RUNNING".equals(run.status());
            ChantierPreflight pf = preflight.get(c.number());
            cards.add(new ClientBoard.Chantier(workspace.getId(), workspace.getName(), workspace.isHostTerminal(),
                    c.number(), c.title(), c.phase(), c.phaseLabel(), c.focus(), c.openedAt(), c.done(), c.total(),
                    nextStep(c), c.unblocker(), pf != null && pf.ready(),
                    pf == null ? List.of() : pf.reasons().stream().map(ChantierPreflight.Reason::label).toList(),
                    last, idle, idle >= dormantAfterDays, running || (c.focus() && turn.isPresent()),
                    c.focus() ? question : null, run));
        }
        return cards;
    }

    private static ClientBoard.NextStep nextStep(SubjectJourneyResponse.OpenChantier c) {
        if (c.plan() == null) {
            return null;
        }
        List<SubjectJourneyResponse.Step> steps = c.plan().steps();
        for (int i = 0; i < steps.size(); i++) {
            String status = steps.get(i).status();
            if (!"FAIT".equals(status) && !"VERIFIE".equals(status)) {
                return new ClientBoard.NextStep(i + 1, steps.get(i).title());
            }
        }
        return null;
    }

    private static OffsetDateTime latest(OffsetDateTime a, OffsetDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    /** La question en suspens sur le tour vivant, lisible ; {@code null} si rien n'attend. */
    static String question(LiveTurn turn) {
        if (turn.pendingQuestion().isPresent()) {
            String text = firstQuestion(turn.pendingQuestion().get().questionsJson());
            return bound(text == null ? "Une question attend votre réponse." : text);
        }
        if (turn.pendingApproval().isPresent()) {
            String tool = turn.pendingApproval().get().tool();
            return bound("Autorisation demandée" + (tool == null || tool.isBlank() ? "." : " : " + tool));
        }
        return null;
    }

    /** Le texte de la première question d'un lot sérialisé ({@code [{header, question, …}]}), ou {@code null}. */
    static String firstQuestion(String questionsJson) {
        if (questionsJson == null || questionsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(questionsJson);
            if (node != null && node.isArray() && !node.isEmpty()) {
                JsonNode first = node.get(0);
                String text = first.path("question").asText(null);
                if (text == null || text.isBlank()) {
                    text = first.path("header").asText(null);
                }
                return text == null || text.isBlank() ? null : text.strip();
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
        return null;
    }

    private static String bound(String text) {
        return text.length() <= MAX_QUESTION ? text : text.substring(0, MAX_QUESTION - 1) + "…";
    }

    private static ClientBoard.Counts counts(List<ClientBoard.Chantier> cards) {
        int outil = 0;
        int moi = 0;
        int autrui = 0;
        int live = 0;
        int dormant = 0;
        for (ClientBoard.Chantier c : cards) {
            if (c.unblocker() != null) {
                switch (c.unblocker().who()) {
                    case OUTIL -> outil++;
                    case MOI -> moi++;
                    case AUTRUI -> autrui++;
                    default -> {
                        // aucune autre valeur
                    }
                }
            }
            if (c.live()) {
                live++;
            }
            if (c.dormant()) {
                dormant++;
            }
        }
        return new ClientBoard.Counts(outil, moi, autrui, live, dormant);
    }
}
