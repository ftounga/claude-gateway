package fr.claudegateway.terminals;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.terminals.dto.LiveTerminalsResponse;

/**
 * Le <b>registre des terminaux vivants</b> (F-70 / SF-70-01) : qui vit, combien, et à partir de
 * quand on refuse.
 *
 * <p><b>Ce qui prend une place</b> : un onglet de terminal <b>ouvert</b>, pas un tour en cours. Le
 * signe de vie demandé par le PO — une pastille et le mot « connecté » — doit être visible quand
 * rien ne tourne ; et le hors-périmètre (« un agent qui travaille pendant que l'onglet est fermé »)
 * dit la même chose : la vie est liée à l'onglet.</p>
 *
 * <p><b>Pourquoi quatre</b> : quatre flux vivants, ce sont quatre consommations simultanées, donc
 * quatre tours facturés en parallèle. Le plafond n'est pas une contrainte technique, c'est un
 * garde-fou de dépense — d'où la borne dure à 4 quelle que soit la configuration.</p>
 *
 * <p><b>La course</b> : deux onglets qui prennent la dernière place en même temps insèrent tous les
 * deux, puis <b>recomptent</b>. Celui qui n'est pas dans les {@code limit} places les plus anciennes
 * retire la sienne et reçoit le refus. Aucun verrou de base n'est nécessaire, le résultat est le
 * même sur PostgreSQL et sur H2, et le nombre de survivants ne dépasse jamais le plafond.</p>
 *
 * <p><b>Isolation</b> : {@code userId} vient toujours du jeton, jamais du corps de la requête ; la
 * propriété du projet est vérifiée par {@link WorkspaceService#requireOwned} <b>avant</b> toute
 * écriture, et chaque lecture du registre filtre sur {@code user_id}.</p>
 */
@Service
public class LiveTerminalService {

    /**
     * Plafond dur, non contournable par la configuration (décision PO). Une valeur plus haute ne
     * serait pas un réglage : ce serait supprimer le garde-fou de dépense.
     */
    public static final int MAX_LIMIT = 4;

    /** Délai de grâce minimal : en dessous, un battement un peu en retard perdrait sa place. */
    static final Duration MIN_TTL = Duration.ofSeconds(30);

    /** Délai de grâce maximal : au-delà, un onglet fermé bloquerait une place trop longtemps. */
    static final Duration MAX_TTL = Duration.ofMinutes(10);

    private final LiveTerminalRepository repository;
    private final WorkspaceService workspaceService;
    private final WorkspaceRepository workspaceRepository;
    private final RunnerHostRepository hostRepository;
    private final int limit;
    private final Duration ttl;

    public LiveTerminalService(
            LiveTerminalRepository repository,
            WorkspaceService workspaceService,
            WorkspaceRepository workspaceRepository,
            RunnerHostRepository hostRepository,
            @Value("${app.terminals.live.limit:4}") int limit,
            @Value("${app.terminals.live.ttl:PT90S}") Duration ttl) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.workspaceRepository = workspaceRepository;
        this.hostRepository = hostRepository;
        this.limit = Math.clamp(limit, 1, MAX_LIMIT);
        this.ttl = clamp(ttl, MIN_TTL, MAX_TTL);
    }

    /** Plafond effectif, après bornage. */
    public int limit() {
        return limit;
    }

    /** Délai de grâce effectif, après bornage — visible pour le test, jamais rendu au client. */
    Duration ttl() {
        return ttl;
    }

    /**
     * Prend une place pour cet onglet, ou renouvelle la sienne.
     *
     * @param userId      propriétaire, issu du jeton
     * @param workspaceId projet ouvert dans l'onglet — sa propriété est vérifiée
     * @param sessionId   identifiant d'onglet
     * @return l'état complet du registre après l'opération
     * @throws LiveTerminalLimitReachedException si le plafond est atteint (aucune ligne laissée)
     */
    @Transactional
    public LiveTerminalsResponse claim(UUID userId, UUID workspaceId, String sessionId) {
        // Isolation d'abord : un projet qui n'est pas à lui est INEXISTANT (404), et rien n'est
        // écrit. Vérifier après l'insertion laisserait une ligne pour un projet d'autrui.
        workspaceService.requireOwned(userId, workspaceId);

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minus(ttl);
        repository.deleteStale(userId, cutoff);

        Optional<LiveTerminal> existing = repository.findByUserIdAndSessionId(userId, sessionId);
        if (existing.isPresent()) {
            // Battement de cœur : le même appel sert à prendre et à tenir. Un onglet qui a changé
            // de projet garde sa place plutôt que d'en libérer une pour en reprendre une autre.
            LiveTerminal terminal = existing.get();
            terminal.setWorkspaceId(workspaceId);
            terminal.setLastSeenAt(now);
            repository.saveAndFlush(terminal);
            return describe(userId, cutoff);
        }

        LiveTerminal claimed = repository.saveAndFlush(LiveTerminal.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .sessionId(sessionId)
                .openedAt(now)
                .lastSeenAt(now)
                .build());

        List<LiveTerminal> live = repository
                .findByUserIdAndLastSeenAtAfterOrderByOpenedAtAsc(userId, cutoff);
        if (live.size() > limit && !isKept(live, claimed)) {
            // On ne laisse jamais de trace d'une place refusée : l'écran qui reçoit le 409 doit
            // pouvoir relire le registre et y compter EXACTEMENT `limit` terminaux.
            repository.delete(claimed);
            repository.flush();
            throw new LiveTerminalLimitReachedException(limit);
        }
        return describe(userId, cutoff);
    }

    /**
     * Libère la place de cet onglet. <b>Idempotent</b> : libérer une place déjà libre n'est pas une
     * erreur — le geste part aussi à la fermeture de l'onglet, où l'on ne saura jamais s'il est
     * arrivé.
     */
    @Transactional
    public void release(UUID userId, String sessionId) {
        repository.deleteByUserIdAndSessionId(userId, sessionId);
    }

    /** L'état du registre, sans rien prendre ni renouveler. */
    @Transactional(readOnly = true)
    public LiveTerminalsResponse snapshot(UUID userId) {
        return describe(userId, OffsetDateTime.now().minus(ttl));
    }

    /**
     * Les projets d'un utilisateur dont un terminal est vivant maintenant — une seule lecture, pour
     * les écrans qui affichent beaucoup de projets (la vue d'ensemble des postes).
     */
    @Transactional(readOnly = true)
    public Set<UUID> liveWorkspaceIds(UUID userId) {
        Set<UUID> ids = new HashSet<>();
        for (LiveTerminal terminal : repository.findByUserIdAndLastSeenAtAfterOrderByOpenedAtAsc(
                userId, OffsetDateTime.now().minus(ttl))) {
            ids.add(terminal.getWorkspaceId());
        }
        return ids;
    }

    // ------------------------------------------------------------------ interne

    /** Vrai si cette place fait partie des {@code limit} plus anciennes — celles qui restent. */
    private boolean isKept(List<LiveTerminal> live, LiveTerminal claimed) {
        return live.stream().limit(limit)
                .anyMatch(terminal -> terminal.getId().equals(claimed.getId()));
    }

    private LiveTerminalsResponse describe(UUID userId, OffsetDateTime cutoff) {
        List<LiveTerminal> live = repository
                .findByUserIdAndLastSeenAtAfterOrderByOpenedAtAsc(userId, cutoff);
        if (live.isEmpty()) {
            return new LiveTerminalsResponse(limit, 0, List.of());
        }
        Map<UUID, Workspace> projects = new HashMap<>();
        for (Workspace workspace : workspaceRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            projects.put(workspace.getId(), workspace);
        }
        // Le nom du poste vient des postes DE L'UTILISATEUR, jamais du host_id du projet : règle
        // posée en SF-49-03 pour qu'un projet pointant vers la machine d'un autre n'en révèle rien.
        Map<UUID, String> hostNames = new HashMap<>();
        for (RunnerHost host : hostRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            hostNames.put(host.getId(), host.getName());
        }
        List<LiveTerminalsResponse.LiveTerminal> described = live.stream()
                .map(terminal -> {
                    Workspace workspace = projects.get(terminal.getWorkspaceId());
                    UUID hostId = workspace == null ? null : workspace.getHostId();
                    return new LiveTerminalsResponse.LiveTerminal(
                            terminal.getWorkspaceId(),
                            workspace == null ? null : workspace.getName(),
                            hostId,
                            hostId == null ? null : hostNames.get(hostId),
                            terminal.getOpenedAt());
                })
                .toList();
        return new LiveTerminalsResponse(limit, described.size(), described);
    }

    private static Duration clamp(Duration value, Duration min, Duration max) {
        Duration effective = value == null ? min : value;
        if (effective.compareTo(min) < 0) {
            return min;
        }
        return effective.compareTo(max) > 0 ? max : effective;
    }
}
