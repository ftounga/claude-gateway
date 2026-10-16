package fr.claudegateway.atelier.actions;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;

/**
 * <b>La vue par interlocuteur</b> (F-180 / SF-180-05, décisions D5 et D7) : qui bloque quoi, depuis
 * quand, et combien de temps chacun met à répondre. En prod, une personne portait 11 des 20 attentes
 * ouvertes : la relancer une fois vaut mieux que onze.
 *
 * <p>Lecture seule. Une personne = un interlocuteur (SF-180-03) ; sans interlocuteur, le nom normalisé.
 * Les gestes « à toi » ({@code qui = MOI}) n'attendent personne : ils ne forment pas une personne.</p>
 *
 * <p><b>Isolation</b> : le périmètre est le poste du terminal <b>possédé</b> (ou ce terminal s'il est
 * hébergé) ; chaque lecture porte {@code user_id}.</p>
 */
@Service
public class InterlocuteurBoardService {

    /** La fenêtre de la mesure des délais. */
    static final int MEASURE_DAYS = 180;

    private final TerminalActionRepository actions;
    private final WorkspaceRepository workspaces;
    private final InterlocuteurRepository interlocuteurs;
    private final Clock clock;
    private final TerminalActionFollowUp followUp;

    public InterlocuteurBoardService(TerminalActionRepository actions, WorkspaceRepository workspaces,
                                     InterlocuteurRepository interlocuteurs, Clock clock,
                                     TerminalActionFollowUp followUp) {
        this.actions = actions;
        this.workspaces = workspaces;
        this.interlocuteurs = interlocuteurs;
        this.clock = clock;
        this.followUp = followUp;
    }

    /** Une personne et ce qu'on attend d'elle. */
    public record PersonRow(UUID interlocuteurId, String name, String role, int openCount, int demandeCount,
                            OffsetDateTime oldestOpenAt, List<String> subjects, Double avgResponseDays,
                            int responseCount, Map<TerminalActionCategorie, Double> avgByCategorie,
                            List<TerminalActionResponse> open) {
    }

    /** La mesure par nature, sur le poste. */
    public record CategorieRow(TerminalActionCategorie categorie, Double avgResponseDays, int responseCount,
                               int openCount) {
    }

    public record Board(List<PersonRow> people, List<CategorieRow> byCategorie) {
    }

    @Transactional(readOnly = true)
    public Board board(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaces.findByIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new TerminalActionNotFoundException("Terminal introuvable."));
        List<TerminalAction> all = workspace.getHostId() != null
                ? actions.findByUserIdAndHostId(userId, workspace.getHostId())
                : actions.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspace.getId());
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime since = now.minusDays(MEASURE_DAYS);

        Map<UUID, Interlocuteur> known = new HashMap<>();
        if (workspace.getHostId() != null) {
            for (Interlocuteur i : interlocuteurs.findByUserIdAndHostIdOrderByDisplayNameAsc(userId, workspace.getHostId())) {
                known.put(i.getId(), i);
            }
        }
        Map<UUID, String> terminalNames = new HashMap<>();
        terminalNames.put(workspace.getId(), workspace.getName());

        Map<String, Acc> people = new LinkedHashMap<>();
        Map<TerminalActionCategorie, Acc> byCat = new EnumMap<>(TerminalActionCategorie.class);
        for (TerminalAction a : all) {
            if (a.getQui() == TerminalActionQui.MOI) {
                continue; // un geste à soi n'attend personne
            }
            String key = keyOf(a);
            Double delay = delayDays(a, since);
            if (a.getCategorie() != null && (a.isOpen() || delay != null)) {
                Acc c = byCat.computeIfAbsent(a.getCategorie(), k -> new Acc(null, null));
                if (a.isOpen()) {
                    c.openCount++;
                }
                if (delay != null) {
                    c.addDelay(null, delay);
                }
            }
            if (key == null || (!a.isOpen() && delay == null)) {
                continue;
            }
            Acc p = people.computeIfAbsent(key, k -> new Acc(a.getInterlocuteurId() != null
                    ? known.get(a.getInterlocuteurId()) : null, nameOf(a)));
            if (delay != null) {
                p.addDelay(a.getCategorie(), delay);
            }
            if (a.isOpen()) {
                p.open.add(a);
                String terminal = terminalNames.computeIfAbsent(a.getWorkspaceId(),
                        id -> workspaces.findByIdAndUserId(id, userId).map(Workspace::getName).orElse("Projet supprimé"));
                p.subjects.add(terminal);
            }
        }

        List<PersonRow> rows = new ArrayList<>();
        for (Acc p : people.values()) {
            if (p.open.isEmpty()) {
                continue;
            }
            p.open.sort(Comparator.comparing(TerminalAction::getCreatedAt));
            int demande = (int) p.open.stream().filter(a -> a.getStatus() == TerminalActionStatus.DEMANDE).count();
            List<TerminalActionResponse> open = p.open.stream()
                    .map(a -> TerminalActionResponse.from(a, terminalNames.get(a.getWorkspaceId()), followUp.isDue(a, now)))
                    .toList();
            rows.add(new PersonRow(p.interlocuteur == null ? null : p.interlocuteur.getId(),
                    p.interlocuteur == null ? p.name : p.interlocuteur.getDisplayName(),
                    p.interlocuteur == null ? null : p.interlocuteur.getRole(),
                    p.open.size(), demande, p.open.get(0).getCreatedAt(), List.copyOf(p.subjects),
                    p.average(), p.count, p.averageByCategorie(), open));
        }
        rows.sort(Comparator.comparingInt(PersonRow::openCount).reversed()
                .thenComparing(PersonRow::oldestOpenAt));

        List<CategorieRow> cats = new ArrayList<>();
        byCat.forEach((cat, acc) -> cats.add(new CategorieRow(cat, acc.average(), acc.count, acc.openCount)));
        return new Board(rows, cats);
    }

    /** La personne d'une attente : son interlocuteur, à défaut le nom normalisé ; {@code null} sans nom. */
    static String keyOf(TerminalAction a) {
        if (a.getInterlocuteurId() != null) {
            return "id:" + a.getInterlocuteurId();
        }
        String name = nameOf(a);
        String normalized = InterlocuteurService.normalize(name);
        return normalized.isEmpty() ? null : "name:" + normalized;
    }

    private static String nameOf(TerminalAction a) {
        return a.getRequestedTo() != null ? a.getRequestedTo() : a.getPerson();
    }

    /** Le délai demande → réponse en jours (décimal), pour une attente faite et demandée dans la fenêtre. */
    static Double delayDays(TerminalAction a, OffsetDateTime since) {
        if (a.getStatus() != TerminalActionStatus.FAIT || a.getRequestedAt() == null || a.getClosedAt() == null
                || a.getClosedAt().isBefore(since) || a.getClosedAt().isBefore(a.getRequestedAt())) {
            return null;
        }
        return Duration.between(a.getRequestedAt(), a.getClosedAt()).toMinutes() / 1440.0;
    }

    /** L'accumulateur d'une personne ou d'une nature. */
    private static final class Acc {
        final Interlocuteur interlocuteur;
        final String name;
        final List<TerminalAction> open = new ArrayList<>();
        final Set<String> subjects = new LinkedHashSet<>();
        final Map<TerminalActionCategorie, double[]> byCat = new EnumMap<>(TerminalActionCategorie.class);
        double total;
        int count;
        int openCount;

        Acc(Interlocuteur interlocuteur, String name) {
            this.interlocuteur = interlocuteur;
            this.name = name;
        }

        void addDelay(TerminalActionCategorie categorie, double days) {
            total += days;
            count++;
            if (categorie != null) {
                double[] c = byCat.computeIfAbsent(categorie, k -> new double[2]);
                c[0] += days;
                c[1]++;
            }
        }

        Double average() {
            return count == 0 ? null : round(total / count);
        }

        Map<TerminalActionCategorie, Double> averageByCategorie() {
            Map<TerminalActionCategorie, Double> out = new EnumMap<>(TerminalActionCategorie.class);
            byCat.forEach((k, v) -> out.put(k, round(v[0] / v[1])));
            return out;
        }

        private static double round(double v) {
            return Math.round(v * 10) / 10.0;
        }
    }
}
