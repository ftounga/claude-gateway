package fr.claudegateway.jarvis.universe;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.actions.Interlocuteur;
import fr.claudegateway.atelier.actions.InterlocuteurRepository;
import fr.claudegateway.atelier.dashboard.ClientBoard;
import fr.claudegateway.atelier.dashboard.ClientBoardService;
import fr.claudegateway.jarvis.JarvisReturnService;
import fr.claudegateway.runner.RunnerStatusService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.terminals.LiveTerminalService;

/**
 * <b>L'univers de Jarvis</b> (F-234 / SF-234-01, D4) — un agrégat <b>en lecture seule</b>, bâti sur les services
 * existants (postes, sujets, tableau du client F-220, interlocuteurs F-180, « À envoyer » SF-231-06, terminaux
 * vivants F-70), <b>tous appelés avec le {@code user_id} du compte</b>. Un appel au lieu de dizaines, sur téléphone.
 *
 * <p>Best-effort par client : une lecture qui échoue (tableau, interlocuteurs) laisse ce client avec ce qu'on a pu
 * lire, jamais une erreur pour tout l'écran. Aucun contenu ne sort (voir {@link JarvisUniverse}).</p>
 */
@Service
public class JarvisUniverseService {

    private static final Logger log = LoggerFactory.getLogger(JarvisUniverseService.class);

    /** Bornes (D4) : au-delà, l'écran ne saurait plus rien dessiner de lisible. */
    public static final int MAX_CLIENTS = 12;
    public static final int MAX_SUBJECTS = 40;
    public static final int MAX_CHANTIERS = 10;
    public static final int MAX_PEOPLE = 60;
    public static final int MAX_VARIANTS = 5;
    public static final int MAX_RECIPIENTS = 20;
    /** Le nom affiché du client virtuel des sujets sans poste (F-71). */
    public static final String HOSTED_NAME = "Hébergé";
    /** Le nom affiché du terminal du poste (F-74), quel que soit son nom technique. */
    public static final String HOST_TERMINAL_NAME = "Terminal du poste";

    private final RunnerHostService hosts;
    private final RunnerStatusService status;
    private final WorkspaceService workspaces;
    private final ClientBoardService boards;
    private final InterlocuteurRepository interlocuteurs;
    private final JarvisReturnService toSend;
    private final LiveTerminalService liveTerminals;
    private final Clock clock;

    public JarvisUniverseService(RunnerHostService hosts, RunnerStatusService status, WorkspaceService workspaces,
            ClientBoardService boards, InterlocuteurRepository interlocuteurs, JarvisReturnService toSend,
            LiveTerminalService liveTerminals, Clock clock) {
        this.hosts = hosts;
        this.status = status;
        this.workspaces = workspaces;
        this.boards = boards;
        this.interlocuteurs = interlocuteurs;
        this.toSend = toSend;
        this.liveTerminals = liveTerminals;
        this.clock = clock;
    }

    /** L'univers du compte {@code userId} — jamais un paramètre reçu du client. */
    @Transactional(readOnly = true)
    public JarvisUniverse universe(UUID userId) {
        Set<UUID> live = safeLive(userId);
        Map<UUID, List<Workspace>> byHost = new LinkedHashMap<>();
        List<Workspace> hosted = new ArrayList<>();
        for (Workspace w : workspaces.list(userId)) {
            if (!userId.equals(w.getUserId()) || w.isTeamsTerminal() || w.isJarvisThread()) {
                // le terminal Teams n'est pas un sujet qu'on choisit à la voix ; le fil Jarvis (F-245 / SF-245-01, D1)
                // non plus : on ne s'y délègue pas « comme à un sujet ».
                continue;
            }
            if (w.getHostId() == null) {
                hosted.add(w);
            } else {
                byHost.computeIfAbsent(w.getHostId(), k -> new ArrayList<>()).add(w);
            }
        }
        JarvisReturnService.ToSendList pending = safeToSend(userId);
        Map<UUID, Set<String>> recipientsBySubject = new HashMap<>();
        for (JarvisReturnService.ToSend item : pending.items()) {
            if (item.workspaceId() != null && item.recipient() != null && !item.recipient().isBlank()) {
                recipientsBySubject.computeIfAbsent(item.workspaceId(), k -> new LinkedHashSet<>())
                        .add(item.recipient().strip());
            }
        }

        List<RunnerHost> owned = new ArrayList<>();
        Map<UUID, Boolean> connected = new HashMap<>();
        for (RunnerHost host : hosts.list(userId)) {
            if (!userId.equals(host.getUserId())) {
                continue;
            }
            owned.add(host);
            connected.put(host.getId(), safeConnected(userId, host));
        }
        // Les connectés d'abord, puis par nom : la carte se lit pareil d'une ouverture à l'autre.
        owned.sort(Comparator.comparing((RunnerHost h) -> !connected.get(h.getId()))
                .thenComparing(h -> lower(h.getName())));

        List<JarvisUniverse.Client> clients = new ArrayList<>();
        for (RunnerHost host : owned) {
            if (clients.size() >= MAX_CLIENTS) {
                break;
            }
            clients.add(client(userId, host, connected.get(host.getId()),
                    byHost.getOrDefault(host.getId(), List.of()), live, recipientsBySubject));
        }
        if (!hosted.isEmpty() && clients.size() < MAX_CLIENTS) {
            clients.add(hostedClient(hosted, live, recipientsBySubject));
        }
        return new JarvisUniverse(OffsetDateTime.now(clock), List.copyOf(clients), pending.count());
    }

    private JarvisUniverse.Client client(UUID userId, RunnerHost host, boolean connected, List<Workspace> subjects,
            Set<UUID> live, Map<UUID, Set<String>> recipients) {
        Map<UUID, List<JarvisUniverse.Chantier>> chantiers = new HashMap<>();
        boolean truncated = false;
        try {
            ClientBoard board = boards.board(userId, host.getId());
            for (ClientBoard.Chantier c : board.chantiers()) {
                List<JarvisUniverse.Chantier> list = chantiers.computeIfAbsent(c.workspaceId(),
                        k -> new ArrayList<>());
                if (list.size() >= MAX_CHANTIERS) {
                    truncated = true;
                    continue;
                }
                list.add(chantier(c));
            }
        } catch (RuntimeException e) {
            log.warn("Univers Jarvis : tableau du client illisible ({})", e.getClass().getSimpleName());
        }
        List<Workspace> ordered = new ArrayList<>(subjects);
        // Le terminal du poste d'abord (nœud central du client), puis les sujets par nom.
        ordered.sort(Comparator.comparing((Workspace w) -> !w.isHostTerminal()).thenComparing(w -> lower(w.getName())));
        List<JarvisUniverse.Subject> list = new ArrayList<>();
        Set<String> toSendNames = new LinkedHashSet<>();
        for (Workspace w : ordered) {
            if (list.size() >= MAX_SUBJECTS) {
                truncated = true;
                break;
            }
            list.add(new JarvisUniverse.Subject(w.getId(), w.isHostTerminal() ? HOST_TERMINAL_NAME : w.getName(),
                    w.isHostTerminal(), live.contains(w.getId()),
                    List.copyOf(chantiers.getOrDefault(w.getId(), List.of()))));
            toSendNames.addAll(recipients.getOrDefault(w.getId(), Set.of()));
        }
        List<JarvisUniverse.Person> people = people(userId, host.getId());
        if (people.size() > MAX_PEOPLE) {
            people = people.subList(0, MAX_PEOPLE);
            truncated = true;
        }
        return new JarvisUniverse.Client(host.getId(), host.getName(), false, connected,
                host.getMissionStatus() == null ? null : host.getMissionStatus().name(), List.copyOf(list),
                List.copyOf(people), cap(toSendNames), truncated);
    }

    private JarvisUniverse.Client hostedClient(List<Workspace> subjects, Set<UUID> live,
            Map<UUID, Set<String>> recipients) {
        List<Workspace> ordered = new ArrayList<>(subjects);
        ordered.sort(Comparator.comparing(w -> lower(w.getName())));
        List<JarvisUniverse.Subject> list = new ArrayList<>();
        Set<String> toSendNames = new LinkedHashSet<>();
        for (Workspace w : ordered.subList(0, Math.min(MAX_SUBJECTS, ordered.size()))) {
            list.add(new JarvisUniverse.Subject(w.getId(), w.getName(), false, live.contains(w.getId()), List.of()));
            toSendNames.addAll(recipients.getOrDefault(w.getId(), Set.of()));
        }
        return new JarvisUniverse.Client(null, HOSTED_NAME, true, false, null, List.copyOf(list), List.of(),
                cap(toSendNames), ordered.size() > MAX_SUBJECTS);
    }

    /** Un chantier du tableau, réduit à ce que l'écran dessine : ni question, ni description, ni course. */
    static JarvisUniverse.Chantier chantier(ClientBoard.Chantier c) {
        List<String> waiting = new ArrayList<>();
        if (c.unblocker() != null && c.unblocker().waitingOn() != null && !c.unblocker().waitingOn().isBlank()) {
            waiting.add(c.unblocker().waitingOn().strip());
        }
        JarvisUniverse.Step step = c.nextStep() == null ? null
                : new JarvisUniverse.Step(c.nextStep().rank(), c.nextStep().title());
        return new JarvisUniverse.Chantier(c.number(), c.title(), c.phase(), c.phaseLabel(), c.done(), c.total(),
                step, c.focus(), c.live(), List.copyOf(waiting));
    }

    private List<JarvisUniverse.Person> people(UUID userId, UUID hostId) {
        try {
            List<JarvisUniverse.Person> people = new ArrayList<>();
            for (Interlocuteur i : interlocuteurs.findByUserIdAndHostIdOrderByDisplayNameAsc(userId, hostId)) {
                if (!userId.equals(i.getUserId())) {
                    continue;
                }
                List<String> variants = i.variantList();
                people.add(new JarvisUniverse.Person(i.getDisplayName(),
                        List.copyOf(variants.subList(0, Math.min(MAX_VARIANTS, variants.size()))), i.getRole()));
            }
            return people;
        } catch (RuntimeException e) {
            log.warn("Univers Jarvis : interlocuteurs illisibles ({})", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private boolean safeConnected(UUID userId, RunnerHost host) {
        try {
            return status.statusOf(userId, host).connected();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Set<UUID> safeLive(UUID userId) {
        try {
            return liveTerminals.liveWorkspaceIds(userId);
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    private JarvisReturnService.ToSendList safeToSend(UUID userId) {
        try {
            return toSend.toSend(userId, null);
        } catch (RuntimeException e) {
            log.warn("Univers Jarvis : « À envoyer » illisible ({})", e.getClass().getSimpleName());
            return new JarvisReturnService.ToSendList(0, List.of());
        }
    }

    private static String lower(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static List<String> cap(Set<String> names) {
        return names.stream().limit(MAX_RECIPIENTS).toList();
    }
}
