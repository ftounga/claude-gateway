package fr.claudegateway.atelier.poste;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.atelier.journey.JourneyMode;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyRepository;
import fr.claudegateway.billing.AdministratorEntitlement;
import fr.claudegateway.bilan.SessionBilan;
import fr.claudegateway.bilan.SessionBilanRepository;
import fr.claudegateway.quota.CostWindow;
import fr.claudegateway.quota.ProjectCostAggregate;
import fr.claudegateway.quota.TurnCostView;
import fr.claudegateway.quota.UsageTurnRepository;

/**
 * <b>L'état de chaque sujet du poste, en une lecture</b> (F-178 / SF-178-02, décision D2) — la matière de
 * l'outil {@code sujets_etat}, offert au terminal du poste seulement.
 *
 * <p>Par sujet : dernière activité de la conversation, parcours (F-176 : mode, phase, chantier), attentes
 * ouvertes (F-175), dernier bilan de session (F-155) et dépense de la semaine (F-143). Rien n'est calculé
 * de neuf : ce sont des <b>lectures</b> de ce que les autres features rangent déjà.</p>
 *
 * <p><b>Isolation</b> : tout part du poste du terminal <b>possédé</b> ({@code hostId} vient de l'entité,
 * jamais du modèle) ; chaque lecture porte {@code user_id}. Les montants en euros ne sont montrés qu'à un
 * administrateur — même règle que le coût du tour ({@link TurnCostView}) ; sinon, le nombre de tours.</p>
 *
 * <p><b>Bornes</b> : {@link #MAX_SUBJECTS} sujets, {@link #MAX_ATTENTES} attentes citées par sujet,
 * descriptions tronquées — le résultat reste lisible et borné, quelle que soit la taille du poste.</p>
 */
@Service
public class SubjectsStateService {

    static final int MAX_SUBJECTS = 30;
    static final int MAX_ATTENTES = 3;
    static final int MAX_ATTENTE_CHARS = 100;

    private final WorkspaceService workspaceService;
    private final AtelierMessageRepository messages;
    private final SubjectJourneyRepository journeys;
    private final TerminalActionRepository actions;
    private final SessionBilanRepository bilans;
    private final UsageTurnRepository turns;
    private final TurnCostView costView;
    private final AdministratorEntitlement administrators;
    private final Clock clock;

    public SubjectsStateService(WorkspaceService workspaceService, AtelierMessageRepository messages,
            SubjectJourneyRepository journeys, TerminalActionRepository actions, SessionBilanRepository bilans,
            UsageTurnRepository turns, TurnCostView costView, AdministratorEntitlement administrators,
            Clock clock) {
        this.workspaceService = workspaceService;
        this.messages = messages;
        this.journeys = journeys;
        this.actions = actions;
        this.bilans = bilans;
        this.turns = turns;
        this.costView = costView;
        this.administrators = administrators;
        this.clock = clock;
    }

    /**
     * Le tableau des sujets du poste, en texte pour l'agent.
     *
     * @param userId utilisateur du tour
     * @param hostId poste du terminal possédé
     */
    @Transactional(readOnly = true)
    public String describe(UUID userId, UUID hostId) {
        List<Workspace> subjects = new ArrayList<>();
        for (Workspace w : workspaceService.listByHost(userId, hostId)) {
            if (userId.equals(w.getUserId()) && hostId.equals(w.getHostId())) {
                subjects.add(w);
            }
        }
        if (subjects.isEmpty()) {
            return "Aucun sujet sur ce poste pour l'instant (seul le terminal du poste existe).";
        }
        Map<UUID, Workspace> byId = new LinkedHashMap<>();
        subjects.forEach(w -> byId.put(w.getId(), w));

        Map<UUID, OffsetDateTime> lastAt = new HashMap<>();
        for (Object[] row : messages.lastActivityByWorkspace(byId.keySet(), userId)) {
            if (row != null && row.length == 2 && row[0] instanceof UUID id && row[1] instanceof OffsetDateTime at) {
                lastAt.put(id, at);
            }
        }
        Map<UUID, SubjectJourney> journeyOf = new HashMap<>();
        for (SubjectJourney j : journeys.findByUserIdAndWorkspaceIdIn(userId, byId.keySet())) {
            journeyOf.put(j.getWorkspaceId(), j);
        }
        Map<UUID, List<TerminalAction>> openOf = new HashMap<>();
        for (TerminalAction a : actions.findByUserIdAndHostIdAndStatusIn(userId, hostId,
                TerminalActionStatus.OPEN_STATES)) {
            if (a.getWorkspaceId() != null && byId.containsKey(a.getWorkspaceId())) {
                openOf.computeIfAbsent(a.getWorkspaceId(), k -> new ArrayList<>()).add(a);
            }
        }
        CostWindow week = CostWindow.currentWeek(clock);
        Map<UUID, ProjectCostAggregate> weekOf = new HashMap<>();
        for (ProjectCostAggregate row : turns.aggregateCostByProject(userId, week.start(), week.end())) {
            if (row.getWorkspaceId() != null) {
                weekOf.put(row.getWorkspaceId(), row);
            }
        }
        boolean admin = administrators.isAdministrator(userId);

        // Le plus récemment actif d'abord : c'est l'ordre dans lequel on se demande « où en est-on ».
        subjects.sort(Comparator.comparing((Workspace w) -> lastAt.get(w.getId()),
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Workspace::getName, Comparator.nullsLast(String::compareToIgnoreCase)));

        StringBuilder out = new StringBuilder();
        out.append("État des sujets du poste — ").append(subjects.size())
                .append(subjects.size() > 1 ? " sujets" : " sujet")
                .append(", du plus récemment actif au moins actif (semaine du ").append(week.firstDay())
                .append(" au ").append(week.lastDay()).append(") :\n");
        int shown = 0;
        for (Workspace w : subjects) {
            if (shown++ >= MAX_SUBJECTS) {
                out.append("\n… ").append(subjects.size() - MAX_SUBJECTS).append(" sujet(s) de plus non détaillé(s).");
                break;
            }
            UUID id = w.getId();
            out.append("\n## ").append(w.getName()).append('\n');
            OffsetDateTime at = lastAt.get(id);
            out.append("- Dernière activité : ").append(at == null ? "aucune conversation" : at.toLocalDate())
                    .append('\n');
            out.append("- Parcours : ").append(journeyLine(journeyOf.get(id))).append('\n');
            out.append("- Attentes ouvertes : ").append(attentesLine(openOf.getOrDefault(id, List.of())))
                    .append('\n');
            out.append("- Dernier bilan : ").append(bilanLine(userId, id, admin)).append('\n');
            out.append("- Semaine : ").append(weekLine(weekOf.get(id), admin)).append('\n');
        }
        return out.toString().strip();
    }

    private static String journeyLine(SubjectJourney journey) {
        if (journey == null || journey.getMode() == null || journey.getMode() == JourneyMode.LIBRE) {
            return "Libre";
        }
        StringBuilder line = new StringBuilder("Guidé");
        if (journey.getPhase() != null) {
            line.append(" · phase ").append(journey.getPhase().label());
        }
        if (journey.getChantierNumber() > 0) {
            line.append(" · chantier n°").append(journey.getChantierNumber());
            if (journey.getChantierTitle() != null && !journey.getChantierTitle().isBlank()) {
                line.append(" « ").append(shorten(journey.getChantierTitle())).append(" »");
            }
        }
        return line.toString();
    }

    private static String attentesLine(List<TerminalAction> open) {
        if (open.isEmpty()) {
            return "aucune";
        }
        List<TerminalAction> sorted = new ArrayList<>(open);
        sorted.sort(Comparator.comparing(TerminalAction::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        StringBuilder line = new StringBuilder().append(open.size()).append(" — ");
        List<String> parts = new ArrayList<>();
        for (TerminalAction a : sorted.subList(0, Math.min(MAX_ATTENTES, sorted.size()))) {
            parts.add("« " + shorten(a.getDescription()) + " » ("
                    + (a.getStatus() == TerminalActionStatus.DEMANDE ? "demandé" : "à faire") + ")");
        }
        line.append(String.join(" ; ", parts));
        if (sorted.size() > MAX_ATTENTES) {
            line.append(" ; …");
        }
        return line.toString();
    }

    private String bilanLine(UUID userId, UUID workspaceId, boolean admin) {
        SessionBilan bilan = bilans.findFirstByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId)
                .orElse(null);
        if (bilan == null) {
            return "aucun";
        }
        StringBuilder line = new StringBuilder();
        line.append(bilan.getCreatedAt() == null ? "?" : bilan.getCreatedAt().toLocalDate())
                .append(" · ").append(bilan.getTurns()).append(" tours");
        if (admin && bilan.getCostEur() != null && bilan.getCostEur().signum() > 0) {
            line.append(" · ").append(euros(bilan.getCostEur()));
        }
        if (bilan.getSuggestionCount() > 0) {
            line.append(" · ").append(bilan.getSuggestionCount()).append(" suggestion(s)");
        }
        return line.toString();
    }

    private String weekLine(ProjectCostAggregate week, boolean admin) {
        if (week == null || week.getTurns() == 0) {
            return "aucun tour";
        }
        String amount = admin ? costView.labelFor(week.getCostUsd(), true) : null;
        return (amount == null ? "" : amount + " · ") + week.getTurns() + " tours";
    }

    private static String euros(BigDecimal eur) {
        return eur.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " €";
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX_ATTENTE_CHARS ? flat : flat.substring(0, MAX_ATTENTE_CHARS) + "…";
    }
}
