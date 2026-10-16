package fr.claudegateway.atelier.actions;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;

/**
 * <b>Les lectures qui traversent les terminaux</b> (F-154 / SF-154-03, F-175 / SF-175-01) : les
 * attentes ouvertes du compte, et le tableau d'un poste.
 *
 * <p>Séparée de {@link TerminalActionService} à dessein : celui-ci porte la règle « un projet, ses
 * actions », avec {@code requireOwned} en tête de chaque méthode. Ici la lecture est <b>volontairement
 * transverse</b> — mais jamais entre comptes : le {@code user_id} est sur chaque requête, et le
 * {@code host_id} du tableau vient du terminal <b>possédé</b>, jamais du client.</p>
 */
@Service
public class TerminalActionQueryService {

    /** Au-delà, ce n'est plus « ailleurs », c'est un inventaire : on coupe et on le dit à l'écran. */
    static final int MAX_ELSEWHERE = 100;

    /** Combien de jours une attente fermée reste visible dans le tableau (« Rétablir »). */
    static final int RECENTLY_CLOSED_DAYS = 7;

    private final TerminalActionRepository repository;
    private final WorkspaceRepository workspaces;
    private final Clock clock;
    private final TerminalActionFollowUp followUp;

    public TerminalActionQueryService(TerminalActionRepository repository,
                                      WorkspaceRepository workspaces,
                                      Clock clock,
                                      TerminalActionFollowUp followUp) {
        this.repository = repository;
        this.workspaces = workspaces;
        this.clock = clock;
        this.followUp = followUp;
    }

    /**
     * Les actions ouvertes du compte, hors le projet donné, les plus anciennes d'abord.
     *
     * @param excludeWorkspaceId projet à retirer (celui du terminal courant) ; ignoré s'il n'est pas
     *                           un identifiant lisible — un paramètre douteux ne doit pas faire 500
     */
    @Transactional(readOnly = true)
    public List<TerminalActionElsewhereResponse> openElsewhere(UUID userId, String excludeWorkspaceId) {
        UUID exclude = parse(excludeWorkspaceId);
        Map<UUID, String> names = new HashMap<>();
        return repository.findByUserIdAndStatusInOrderByCreatedAtAsc(userId, TerminalActionStatus.OPEN_STATES)
                .stream()
                .filter(action -> !action.getWorkspaceId().equals(exclude))
                .limit(MAX_ELSEWHERE)
                .map(action -> TerminalActionElsewhereResponse.from(action,
                        names.computeIfAbsent(action.getWorkspaceId(), id -> nameOf(userId, id))))
                .toList();
    }

    /**
     * <b>Le tableau des attentes vu d'un terminal</b> (F-175 / SF-175-01) : celles de ce terminal,
     * puis celles du reste de son poste. Un terminal hébergé (sans poste) ne voit que les siennes.
     *
     * @throws TerminalActionNotFoundException terminal inconnu ou d'un autre compte (404)
     */
    @Transactional(readOnly = true)
    public TerminalActionBoardResponse board(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaces.findByIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new TerminalActionNotFoundException("Terminal introuvable."));
        UUID hostId = workspace.getHostId();
        OffsetDateTime since = OffsetDateTime.now(clock).minusDays(RECENTLY_CLOSED_DAYS);

        List<TerminalAction> all = hostId == null
                ? repository.findBoardOfWorkspace(userId, workspaceId, TerminalActionStatus.OPEN_STATES, since)
                : repository.findBoardOfHost(userId, hostId, TerminalActionStatus.OPEN_STATES, since);

        Map<UUID, String> names = new HashMap<>();
        names.put(workspaceId, workspace.getName());
        Map<UUID, Map<String, int[]>> stepIndex = new HashMap<>();
        Map<UUID, List<String>> stepTitles = new HashMap<>();
        indexBlockedSteps(userId, all, stepIndex, stepTitles);
        Map<UUID, String> subjectNames = subjectNamesOf(userId, hostId, all);
        Map<UUID, String> interlocuteurNames = interlocuteurNamesOf(userId, hostId, all);
        List<TerminalActionResponse> here = new ArrayList<>();
        List<TerminalActionResponse> elsewhere = new ArrayList<>();
        int aFaire = 0;
        int demande = 0;
        int aRelancer = 0;
        int moi = 0;
        OffsetDateTime oldest = null;
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (TerminalAction action : all) {
            String name = names.computeIfAbsent(action.getWorkspaceId(), id -> nameOf(userId, id));
            boolean due = followUp.isDue(action, now);
            if (due) {
                aRelancer++;
            }
            if (action.isOpen() && action.getQui() == TerminalActionQui.MOI) {
                moi++;
            }
            TerminalActionResponse response = TerminalActionResponse.from(action, name, due).withContext(
                    action.getSubjectId() == null ? null : subjectNames.get(action.getSubjectId()),
                    blockedStepOf(action, name, stepIndex, stepTitles))
                    .withInterlocuteur(action.getInterlocuteurId() == null ? null
                            : interlocuteurNames.get(action.getInterlocuteurId()));
            if (action.getWorkspaceId().equals(workspaceId)) {
                here.add(response);
            } else if (elsewhere.size() < MAX_ELSEWHERE) {
                elsewhere.add(response);
            }
            if (action.getStatus() == TerminalActionStatus.A_FAIRE) {
                aFaire++;
            } else if (action.getStatus() == TerminalActionStatus.DEMANDE) {
                demande++;
            }
            if (action.isOpen() && (oldest == null || action.getCreatedAt().isBefore(oldest))) {
                oldest = action.getCreatedAt();
            }
        }
        return new TerminalActionBoardResponse(hostId, here, elsewhere, aFaire, demande, aRelancer, oldest, moi);
    }

    /** Les plans F-176 des terminaux du tableau (F-180 / SF-180-02) ; absent = pas d'étape montrée. */
    private fr.claudegateway.atelier.journey.SubjectJourneyRepository journeys;

    /** Les noms des sujets du Radar (F-180 / SF-180-02) ; absent = pas de nom montré. */
    private TerminalActionSubjectResolver subjects;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJourneys(fr.claudegateway.atelier.journey.SubjectJourneyRepository journeys) {
        this.journeys = journeys;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSubjects(TerminalActionSubjectResolver subjects) {
        this.subjects = subjects;
    }

    /**
     * Pour chaque terminal du tableau, les étapes de son plan qui attendent une clé : clé normalisée →
     * rang (1…). Lecture sous {@code user_id}. Best-effort : un plan illisible ne montre rien.
     */
    private void indexBlockedSteps(UUID userId, List<TerminalAction> all, Map<UUID, Map<String, int[]>> index,
                                   Map<UUID, List<String>> titles) {
        if (journeys == null || all.isEmpty()) {
            return;
        }
        try {
            java.util.Set<UUID> ids = new java.util.HashSet<>();
            all.forEach(a -> ids.add(a.getWorkspaceId()));
            for (var journey : journeys.findByUserIdAndWorkspaceIdIn(userId, ids)) {
                var steps = fr.claudegateway.atelier.journey.JourneyPlan.fromJson(journey.getPlanJson()).steps();
                Map<String, int[]> byKey = new HashMap<>();
                List<String> stepTitles = new ArrayList<>();
                for (int i = 0; i < steps.size(); i++) {
                    stepTitles.add(steps.get(i).title());
                    String waits = steps.get(i).waitsOn();
                    if (waits != null && !waits.isBlank()) {
                        byKey.putIfAbsent(TerminalActionService.normalizeKey(waits), new int[] {i + 1});
                    }
                }
                index.put(journey.getWorkspaceId(), byKey);
                titles.put(journey.getWorkspaceId(), stepTitles);
            }
        } catch (RuntimeException e) {
            index.clear();
        }
    }

    /** L'étape de SON terminal que l'attente bloque, s'il y en a une. */
    static TerminalActionResponse.BlockedStep blockedStepOf(TerminalAction action, String terminalName,
                                                            Map<UUID, Map<String, int[]>> index,
                                                            Map<UUID, List<String>> titles) {
        if (action.getDedupKey() == null) {
            return null;
        }
        Map<String, int[]> byKey = index.get(action.getWorkspaceId());
        int[] rank = byKey == null ? null : byKey.get(action.getDedupKey());
        if (rank == null) {
            return null;
        }
        List<String> stepTitles = titles.getOrDefault(action.getWorkspaceId(), List.of());
        String title = rank[0] - 1 < stepTitles.size() ? stepTitles.get(rank[0] - 1) : null;
        return new TerminalActionResponse.BlockedStep(rank[0], title, terminalName);
    }

    /** Les interlocuteurs normalisés (F-180 / SF-180-03) ; absent = pas de nom normalisé montré. */
    private InterlocuteurService interlocuteurs;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setInterlocuteurs(InterlocuteurService interlocuteurs) {
        this.interlocuteurs = interlocuteurs;
    }

    private Map<UUID, String> interlocuteurNamesOf(UUID userId, UUID hostId, List<TerminalAction> all) {
        if (interlocuteurs == null || hostId == null) {
            return Map.of();
        }
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        all.forEach(a -> {
            if (a.getInterlocuteurId() != null) {
                ids.add(a.getInterlocuteurId());
            }
        });
        return interlocuteurs.namesOf(userId, hostId, ids);
    }

    private Map<UUID, String> subjectNamesOf(UUID userId, UUID hostId, List<TerminalAction> all) {
        if (subjects == null || hostId == null) {
            return Map.of();
        }
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        all.forEach(a -> {
            if (a.getSubjectId() != null) {
                ids.add(a.getSubjectId());
            }
        });
        return subjects.namesOf(userId, hostId, ids);
    }

    /**
     * <b>Les compteurs partout</b> (F-175 / SF-175-06) : les attentes ouvertes du compte, comptées par
     * poste (le rail de la Forge) et par terminal (la mosaïque). Une seule lecture, sous {@code user_id}.
     */
    @Transactional(readOnly = true)
    public TerminalActionSummaryResponse summary(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<UUID, int[]> byHost = new java.util.LinkedHashMap<>();
        Map<UUID, OffsetDateTime> oldestByHost = new HashMap<>();
        Map<UUID, int[]> byWorkspace = new java.util.LinkedHashMap<>();
        for (TerminalAction action : repository.findByUserIdAndStatusInOrderByCreatedAtAsc(
                userId, TerminalActionStatus.OPEN_STATES)) {
            int slot = action.getStatus() == TerminalActionStatus.A_FAIRE ? 0 : 1;
            boolean due = followUp.isDue(action, now);
            int[] w = byWorkspace.computeIfAbsent(action.getWorkspaceId(), id -> new int[3]);
            w[slot]++;
            if (due) {
                w[2]++;
            }
            if (action.getHostId() != null) {
                int[] h = byHost.computeIfAbsent(action.getHostId(), id -> new int[3]);
                h[slot]++;
                if (due) {
                    h[2]++;
                }
                oldestByHost.merge(action.getHostId(), action.getCreatedAt(),
                        (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        List<TerminalActionSummaryResponse.Count> hosts = byHost.entrySet().stream()
                .map(e -> new TerminalActionSummaryResponse.Count(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[2], oldestByHost.get(e.getKey())))
                .toList();
        List<TerminalActionSummaryResponse.Count> terminals = byWorkspace.entrySet().stream()
                .map(e -> new TerminalActionSummaryResponse.Count(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[2], null))
                .toList();
        return new TerminalActionSummaryResponse(hosts, terminals);
    }

    /** Le nom du projet, relu <b>sous l'isolation</b> — jamais par identifiant seul. */
    private String nameOf(UUID userId, UUID workspaceId) {
        return workspaces.findByIdAndUserId(workspaceId, userId)
                .map(Workspace::getName)
                .orElse("Projet supprimé");
    }

    private static UUID parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
