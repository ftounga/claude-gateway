package fr.claudegateway.terminals;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import fr.claudegateway.terminals.dto.TerminalPreview;

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
 * <p><b>La course entre deux onglets différents</b> : deux onglets qui prennent la dernière place en
 * même temps insèrent tous les deux, puis <b>recomptent</b>. Celui qui n'est pas dans les
 * {@code limit} places les plus anciennes retire la sienne et reçoit le refus. Aucun verrou de base
 * n'est nécessaire et le résultat est le même sur PostgreSQL et sur H2. Le recompte lit les places
 * <b>commitées</b> : dans une photo-finish à la milliseconde, deux prises peuvent donc ne pas se
 * voir et une cinquième place survivre. C'est le comportement de F-70, délibérément conservé —
 * resserrer ce compte demanderait un verrou, c'est-à-dire <b>changer le plafond</b>, hors périmètre
 * (voir l'arbitrage A4 du cadrage F-78).</p>
 *
 * <p><b>La course d'un onglet avec lui-même</b> (F-78) : c'est l'autre course, et c'est celle qui a
 * brûlé la production le 2026-09-12. Prendre une place se faisait en deux temps — chercher la fiche,
 * l'insérer si elle manquait — et deux battements du même onglet arrivant ensemble ne trouvaient
 * rien tous les deux. Prendre une place est désormais <b>une seule écriture</b> : on renouvelle, et
 * si personne n'était là, on insère avec le conflit absorbé par le moteur
 * ({@link LiveTerminalClaimWriter}). Il n'y a plus de « entre les deux ».</p>
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

    /**
     * Nombre de tours de la boucle « renouveler, sinon prendre » (F-78). Un seul suffit en
     * pratique : les deux moteurs <b>attendent</b> la fin de la transaction jumelle avant de dire
     * que la place est prise. Le second tour ne sert qu'au cas où ce jumeau a été refusé au plafond
     * et annulé entre-temps ; le troisième est de la pure prudence.
     */
    private static final int CLAIM_ATTEMPTS = 3;

    private final LiveTerminalRepository repository;
    private final LiveTerminalClaimWriter claimWriter;
    private final WorkspaceService workspaceService;
    private final WorkspaceRepository workspaceRepository;
    private final RunnerHostRepository hostRepository;
    private final int limit;
    private final Duration ttl;

    public LiveTerminalService(
            LiveTerminalRepository repository,
            LiveTerminalClaimWriter claimWriter,
            WorkspaceService workspaceService,
            WorkspaceRepository workspaceRepository,
            RunnerHostRepository hostRepository,
            @Value("${app.terminals.live.limit:4}") int limit,
            @Value("${app.terminals.live.ttl:PT90S}") Duration ttl) {
        this.repository = repository;
        this.claimWriter = claimWriter;
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
        return claim(userId, workspaceId, sessionId, null);
    }

    /**
     * Prend une place pour cet onglet ou renouvelle la sienne, et y range <b>ce qu'il est en train
     * de faire</b> (F-76 / SF-76-01).
     *
     * <p>Un {@code preview} nul <b>n'efface rien</b> : c'est l'appel d'un écran qui n'a rien de neuf
     * à dire, ou d'un écran antérieur à F-76. Pour dire « il ne se passe plus rien », on envoie un
     * aperçu {@code IDLE} — le silence n'est pas une affirmation.</p>
     *
     * @param preview aperçu déjà validé par la couche web, borné et nettoyé ici
     * @throws LiveTerminalLimitReachedException si le plafond est atteint (aucune ligne laissée)
     */
    @Transactional
    public LiveTerminalsResponse claim(UUID userId, UUID workspaceId, String sessionId,
            TerminalPreview preview) {
        // Isolation d'abord : un projet qui n'est pas à lui est INEXISTANT (404), et rien n'est
        // écrit. Vérifier après l'insertion laisserait une ligne pour un projet d'autrui.
        workspaceService.requireOwned(userId, workspaceId);

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minus(ttl);
        repository.deleteStale(userId, cutoff);

        // La fiche telle qu'elle doit être après cet appel — identifiant compris, parce qu'il
        // faudra reconnaître SA place au moment d'arbitrer le plafond.
        LiveTerminal place = LiveTerminal.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .workspaceId(workspaceId)
                .sessionId(sessionId)
                .openedAt(now)
                .lastSeenAt(now)
                .build();
        apply(place, preview, now);

        for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
            // (1) RENOUVELER D'ABORD — le cas de loin le plus fréquent : un onglet ouvert bat
            // plusieurs fois par minute et ne prend sa place qu'une fois. Le nombre de lignes
            // touchées EST la réponse, il n'y a rien à lire avant. Renouveler n'ajoute aucune
            // place : le plafond n'a donc rien à arbitrer ici.
            if (renew(place, preview != null) > 0) {
                return describe(userId, cutoff);
            }

            // (2) SINON PRENDRE — une seule écriture, dont le conflit est absorbé par le moteur.
            // `false` ne veut pas dire « erreur » : un battement jumeau du même onglet a pris la
            // place pendant qu'on écrivait. On repasse par (1) pour renouveler LA SIENNE.
            if (claimWriter.insertIfAbsent(place)) {
                return describeAfterClaiming(place, cutoff);
            }
        }

        // Trois tours sans place : le jumeau aurait été refusé puis annulé à chaque fois. On rend
        // le registre tel quel plutôt qu'une erreur — le battement suivant, dans quelques
        // secondes, reprendra une place. Un écran qui perd un battement se rattrape ; un écran qui
        // reçoit un 500 affiche une panne.
        return describe(userId, cutoff);
    }

    /** Le renouvellement, avec ou sans aperçu — ne rien dire n'est pas dire qu'il ne se passe rien. */
    private int renew(LiveTerminal place, boolean withPreview) {
        if (!withPreview) {
            return repository.renew(place.getUserId(), place.getSessionId(), place.getWorkspaceId(),
                    place.getLastSeenAt());
        }
        return repository.renewWithPreview(place.getUserId(), place.getSessionId(),
                place.getWorkspaceId(), place.getLastSeenAt(), place.getActivity(),
                place.getActivityDetail(), place.getPreviewLines());
    }

    /**
     * Le registre après une place <b>créée</b> — et le seul endroit où le plafond s'arbitre.
     *
     * <p>On insère puis on recompte, comme depuis F-70 : celui qui n'est pas dans les
     * {@code limit} places les plus anciennes se retire. C'est ce qui rend l'arbitrage identique
     * sur les deux moteurs, sans verrou.</p>
     *
     * <p><b>Seule une place réellement créée passe ici</b> — un renouvellement n'ajoute aucune
     * place et n'a donc rien à faire arbitrer. C'est la propriété que F-78 devait préserver en
     * changeant l'écriture : un upsert qui insérerait d'abord ferait passer chaque battement par le
     * plafond.</p>
     */
    private LiveTerminalsResponse describeAfterClaiming(LiveTerminal claimed,
            OffsetDateTime cutoff) {
        List<LiveTerminal> live = repository
                .findByUserIdAndLastSeenAtAfterOrderByOpenedAtAsc(claimed.getUserId(), cutoff);
        if (live.size() > limit && !isKept(live, claimed)) {
            // On ne laisse jamais de trace d'une place refusée : l'écran qui reçoit le 409 doit
            // pouvoir relire le registre et y compter EXACTEMENT `limit` terminaux. Le retrait est
            // explicite ET la transaction roule en arrière (l'exception est une RuntimeException) :
            // deux garanties pour une seule promesse, c'est voulu.
            repository.deleteByUserIdAndSessionId(claimed.getUserId(), claimed.getSessionId());
            repository.flush();
            throw new LiveTerminalLimitReachedException(limit);
        }
        return describe(claimed.getUserId(), cutoff);
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

    /**
     * <b>L'aperçu vivant de chaque projet</b> de cet utilisateur (F-76 / SF-76-01) — une seule
     * lecture, pour les écrans qui affichent beaucoup de projets (la vue d'ensemble des postes).
     *
     * <p><b>Deux onglets sur le même projet</b> : la vue d'ensemble parle du <i>projet</i>, pas de
     * l'onglet. On garde donc le relevé <b>le plus récent</b> ; et, à instant égal, celui qui
     * <b>attend une autorisation</b> — c'est celui que l'utilisateur doit voir, et le départager
     * autrement reviendrait à tirer à pile ou face sur la seule information qui presse.</p>
     */
    @Transactional(readOnly = true)
    public Map<UUID, TerminalPreview> previewsByWorkspace(UUID userId) {
        Map<UUID, TerminalPreview> previews = new HashMap<>();
        for (LiveTerminal terminal : repository.findByUserIdAndLastSeenAtAfterOrderByOpenedAtAsc(
                userId, OffsetDateTime.now().minus(ttl))) {
            TerminalPreview preview = previewOf(terminal);
            if (preview == null) {
                continue;
            }
            previews.merge(terminal.getWorkspaceId(), preview, LiveTerminalService::mostTelling);
        }
        return previews;
    }

    // ------------------------------------------------------------------ interne

    /** Celui des deux aperçus qu'il faut montrer : l'attente d'abord, puis le plus récent. */
    private static TerminalPreview mostTelling(TerminalPreview current, TerminalPreview candidate) {
        if (current.awaitsApproval() != candidate.awaitsApproval()) {
            return current.awaitsApproval() ? current : candidate;
        }
        if (current.at() == null) {
            return candidate;
        }
        if (candidate.at() == null) {
            return current;
        }
        return candidate.at().isAfter(current.at()) ? candidate : current;
    }

    /**
     * Range l'aperçu sur la fiche, <b>borné et nettoyé ici</b> : la troncature faite par l'écran
     * décrit le client d'aujourd'hui, pas ce que la table accepte.
     */
    private static void apply(LiveTerminal terminal, TerminalPreview preview, OffsetDateTime now) {
        if (preview == null) {
            return;
        }
        terminal.setActivity(preview.activity());
        terminal.setActivityDetail(TerminalPreviewSanitizer.detail(preview.activityDetail()));
        List<String> lines = TerminalPreviewSanitizer.lines(preview.lines());
        terminal.setPreviewLines(lines.isEmpty() ? null : String.join("\n", lines));
        terminal.setActivityAt(now);
    }

    /** L'aperçu d'une fiche, ou {@code null} quand il n'y a rien à montrer. */
    private static TerminalPreview previewOf(LiveTerminal terminal) {
        TerminalPreview preview = new TerminalPreview(
                terminal.getActivity(),
                terminal.getActivityDetail(),
                splitLines(terminal.getPreviewLines()),
                terminal.getActivityAt());
        return preview.isEmpty() ? null : preview;
    }

    /** Découpe les lignes rangées en un seul document. Jamais nul : une liste vide se parcourt. */
    private static List<String> splitLines(String stored) {
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        return List.of(stored.split("\n", -1));
    }

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
                            terminal.getOpenedAt(),
                            terminal.getActivity(),
                            terminal.getActivityDetail(),
                            splitLines(terminal.getPreviewLines()),
                            terminal.getActivityAt());
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
