package fr.claudegateway.atelier.actions;

import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;

/**
 * <b>Les doublons d'attentes, proposés à la fusion</b> (F-180 / SF-180-01, décision D8).
 *
 * <p>En prod, un même accès était éclaté en quatre lignes (compte, jeton, proxy, incident) et des
 * attentes nées avant le dédoublonnage de F-175 se répétaient d'un terminal à l'autre. Ici, rien n'est
 * fusionné d'autorité : les paires probables sont <b>proposées</b>, l'utilisateur répond
 * [Fusionner] ou [Ce n'est pas pareil].</p>
 *
 * <p>Trois signaux, du plus sûr au plus large : la même clé normalisée ({@code KEY}), les mêmes mots
 * significatifs ({@code WORDS}, Jaccard ≥ {@value #WORDS_THRESHOLD}), le même sens ({@code MEANING},
 * pgvector, seulement si la clé d'embeddings est là). La plus ancienne est gardée.</p>
 *
 * <p><b>Isolation</b> : la portée est le poste <b>du terminal possédé</b> (ou ce seul terminal s'il est
 * hébergé), relu sous {@code user_id} ; toute attente désignée est relue sous {@code user_id} puis
 * vérifiée dans la portée — sinon 404.</p>
 */
@Service
public class TerminalActionDuplicateService {

    static final double WORDS_THRESHOLD = 0.6;
    static final int MAX_PAIRS = 20;

    /** Mots vides : ils feraient se ressembler deux demandes qui n'ont rien en commun. */
    static final Set<String> STOP_WORDS = Set.of(
            "les", "des", "une", "pour", "sur", "dans", "avec", "par", "aux", "est", "son", "ses",
            "leur", "qui", "que", "quoi", "mon", "mes", "ton", "tes", "the", "and", "for", "auprès",
            "aupres", "demander", "obtenir", "faire", "avoir", "etre");

    private final TerminalActionRepository repository;
    private final WorkspaceRepository workspaces;
    private final Clock clock;
    private final double maxDistance;

    private TerminalActionSemanticDedup semantic;

    public TerminalActionDuplicateService(TerminalActionRepository repository, WorkspaceRepository workspaces,
            Clock clock, @Value("${app.atelier.actions.duplicate-max-distance:0.30}") double maxDistance) {
        this.repository = repository;
        this.workspaces = workspaces;
        this.clock = clock;
        this.maxDistance = maxDistance;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSemantic(TerminalActionSemanticDedup semantic) {
        this.semantic = semantic;
    }

    /** Pourquoi deux attentes semblent en double. */
    public enum Reason { KEY, WORDS, MEANING }

    /** Une paire proposée : celle qu'on garde (la plus ancienne), celle qu'on fusionnerait, et pourquoi. */
    public record DuplicatePair(TerminalActionResponse keep, TerminalActionResponse duplicate, Reason reason) {
    }

    /** Les doublons probables vus de ce terminal (son poste), les plus sûrs d'abord. */
    @Transactional(readOnly = true)
    public List<DuplicatePair> duplicates(UUID userId, UUID workspaceId) {
        Workspace workspace = owned(userId, workspaceId);
        List<TerminalAction> open = openInScope(userId, workspace).stream()
                .filter(a -> a.getDuplicateDismissedAt() == null)
                .sorted(Comparator.comparing(TerminalAction::getCreatedAt))
                .toList();
        Map<UUID, TerminalAction> byId = new HashMap<>();
        open.forEach(a -> byId.put(a.getId(), a));

        Set<UUID> proposed = new HashSet<>();
        List<Raw> raw = new ArrayList<>();
        for (int i = 0; i < open.size(); i++) {
            for (int j = i + 1; j < open.size(); j++) {
                TerminalAction older = open.get(i);
                TerminalAction newer = open.get(j);
                Reason reason = lexicalReason(older, newer);
                if (reason != null && !proposed.contains(newer.getId())) {
                    raw.add(new Raw(older, newer, reason));
                    proposed.add(newer.getId());
                }
            }
        }
        if (semantic != null) {
            for (TerminalActionEmbeddingStore.Pair pair : semantic.closePairs(userId, workspace.getHostId(),
                    workspace.getId(), maxDistance, MAX_PAIRS)) {
                TerminalAction older = byId.get(pair.older());
                TerminalAction newer = byId.get(pair.newer());
                if (older != null && newer != null && !proposed.contains(newer.getId())) {
                    raw.add(new Raw(older, newer, Reason.MEANING));
                    proposed.add(newer.getId());
                }
            }
        }
        Map<UUID, String> names = new HashMap<>();
        return raw.stream()
                .sorted(Comparator.comparing(Raw::reason))
                .limit(MAX_PAIRS)
                .map(r -> new DuplicatePair(
                        TerminalActionResponse.from(r.older(), names.computeIfAbsent(r.older().getWorkspaceId(),
                                id -> nameOf(userId, id))),
                        TerminalActionResponse.from(r.newer(), names.computeIfAbsent(r.newer().getWorkspaceId(),
                                id -> nameOf(userId, id))),
                        r.reason()))
                .toList();
    }

    /**
     * <b>[Fusionner]</b> : {@code actionId} passe « Annulé » (« Fusionnée avec … »), {@code intoId} est
     * gardée et hérite de la demande partie et de « ce que ça débloque » s'il lui manquait.
     */
    @Transactional
    public TerminalAction merge(UUID userId, UUID workspaceId, UUID actionId, UUID intoId) {
        Workspace workspace = owned(userId, workspaceId);
        if (intoId == null || intoId.equals(actionId)) {
            throw new InvalidTerminalActionException("Fusion impossible : désignez l'attente à garder.");
        }
        TerminalAction duplicate = inScope(userId, workspace, actionId);
        TerminalAction keep = inScope(userId, workspace, intoId);
        if (!duplicate.isOpen() || !keep.isOpen()) {
            throw new InvalidTerminalActionException("Seules deux attentes ouvertes se fusionnent.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (duplicate.getStatus() == TerminalActionStatus.DEMANDE && keep.getStatus() == TerminalActionStatus.A_FAIRE) {
            keep.setStatus(TerminalActionStatus.DEMANDE);
            keep.setRequestedAt(duplicate.getRequestedAt());
            keep.setRequestedTo(duplicate.getRequestedTo());
            keep.setChannel(duplicate.getChannel());
        }
        if (keep.getBlocks() == null && duplicate.getBlocks() != null) {
            keep.setBlocks(duplicate.getBlocks());
        }
        if (keep.getPerson() == null && duplicate.getPerson() != null) {
            keep.setPerson(duplicate.getPerson());
        }
        keep.setUpdatedAt(now);
        repository.save(keep);

        duplicate.setStatus(TerminalActionStatus.ANNULE);
        duplicate.setClosedAt(now);
        duplicate.setClosedReason(clip("Fusionnée avec « " + keep.getDescription() + " »",
                TerminalActionService.MAX_REASON));
        duplicate.setMergedIntoId(keep.getId());
        duplicate.clearProposal();
        duplicate.setReviewPending(false);
        duplicate.setUpdatedAt(now);
        return repository.save(duplicate);
    }

    /** <b>[Ce n'est pas pareil]</b> : l'attente n'est plus proposée comme doublon. */
    @Transactional
    public TerminalAction dismiss(UUID userId, UUID workspaceId, UUID actionId) {
        Workspace workspace = owned(userId, workspaceId);
        TerminalAction action = inScope(userId, workspace, actionId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        action.setDuplicateDismissedAt(now);
        action.setUpdatedAt(now);
        return repository.save(action);
    }

    /** Le signal lexical entre deux attentes, ou {@code null}. */
    static Reason lexicalReason(TerminalAction a, TerminalAction b) {
        if (a.getDedupKey() != null && a.getDedupKey().equals(b.getDedupKey())) {
            return Reason.KEY;
        }
        if (TerminalActionService.normalizeKey(a.getDescription())
                .equals(TerminalActionService.normalizeKey(b.getDescription()))) {
            return Reason.KEY;
        }
        return jaccard(words(a.getDescription()), words(b.getDescription())) >= WORDS_THRESHOLD
                ? Reason.WORDS : null;
    }

    /** Les mots significatifs : minuscules, sans accent, 3 lettres au moins, hors mots vides. */
    static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        String folded = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        for (String token : folded.split("[^a-z0-9]+")) {
            if (token.length() >= 3 && !STOP_WORDS.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private List<TerminalAction> openInScope(UUID userId, Workspace workspace) {
        return workspace.getHostId() == null
                ? repository.findByUserIdAndWorkspaceIdAndStatusInOrderByCreatedAtAsc(userId, workspace.getId(),
                        TerminalActionStatus.OPEN_STATES)
                : repository.findByUserIdAndHostIdAndStatusIn(userId, workspace.getHostId(),
                        TerminalActionStatus.OPEN_STATES);
    }

    /** L'attente, relue sous {@code user_id}, et seulement si elle est de ce terminal ou de son poste. */
    private TerminalAction inScope(UUID userId, Workspace workspace, UUID actionId) {
        Optional<TerminalAction> found = actionId == null ? Optional.empty()
                : repository.findByIdAndUserId(actionId, userId);
        return found.filter(a -> a.getWorkspaceId().equals(workspace.getId())
                        || (workspace.getHostId() != null && workspace.getHostId().equals(a.getHostId())))
                .orElseThrow(() -> new TerminalActionNotFoundException("Attente introuvable."));
    }

    private Workspace owned(UUID userId, UUID workspaceId) {
        return workspaces.findByIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new TerminalActionNotFoundException("Terminal introuvable."));
    }

    private String nameOf(UUID userId, UUID workspaceId) {
        return workspaces.findByIdAndUserId(workspaceId, userId).map(Workspace::getName).orElse("Projet supprimé");
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private record Raw(TerminalAction older, TerminalAction newer, Reason reason) {
    }
}
