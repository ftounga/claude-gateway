package fr.claudegateway.atelier.repoindex;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>L'index de repo persistant</b> : la liste bornée des chemins d'un projet, côté gateway, pour
 * servir l'outil {@code glob} sans aller-retour runner « trouver le fichier » (F-148 / SF-148-07).
 *
 * <p><b>Aide de localisation, jamais autorité.</b> Il ne porte ni contenu ({@code grep} reste sur le
 * runner) ni symboles (décision minimale et sûre du cadrage). Il n'est servi que lorsqu'il ne peut
 * pas mentir — c'est l'appelant qui garantit « amorcé et aucune mutation du tour » ; ici, on refuse
 * simplement d'indexer un repo trop gros (jamais une liste incomplète servie).</p>
 *
 * <p><b>Rien n'est détruit par un échec.</b> Machine muette : l'ancien index reste. <b>Étranglé</b> :
 * un rafraîchissement par fenêtre et par projet. <b>Isolation</b> : {@code user_id} + {@code
 * workspace_id} sur toute lecture/écriture.</p>
 */
@Service
public class RepoIndexStore {

    private static final Logger log = LoggerFactory.getLogger(RepoIndexStore.class);

    static final Duration MIN_INTERVAL = Duration.ofSeconds(30);

    /** Au-delà, le repo n'est pas indexé : on ne sert jamais une liste incomplète. */
    static final int MAX_PATHS = 50_000;
    static final int MAX_CONTENT_CHARS = 4_000_000;

    private final RunnerToolGateway runnerToolGateway;
    private final RepoIndexPathRepository repo;

    private final Map<UUID, Instant> lastRefresh = new ConcurrentHashMap<>();

    public RepoIndexStore(RunnerToolGateway runnerToolGateway, RepoIndexPathRepository repo) {
        this.runnerToolGateway = runnerToolGateway;
        this.repo = repo;
    }

    /** L'index de ce projet est-il amorcé ? */
    @Transactional(readOnly = true)
    public boolean isPrimed(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return false;
        }
        return repo.existsByUserIdAndWorkspaceId(userId, workspaceId);
    }

    /** Les chemins indexés de ce projet, ou une liste vide. */
    @Transactional(readOnly = true)
    public List<String> paths(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return List.of();
        }
        return repo.findByUserIdAndWorkspaceId(userId, workspaceId)
                .map(RepoIndexEntry::getPaths)
                .map(RepoIndexStore::splitPaths)
                .orElseGet(List::of);
    }

    /** Vrai si un chemin exact figure dans l'index de ce projet (F-121 / SF-121-19). */
    @Transactional(readOnly = true)
    public boolean contains(UUID userId, UUID workspaceId, String path) {
        if (userId == null || workspaceId == null || path == null || path.isBlank()) {
            return false;
        }
        return paths(userId, workspaceId).contains(path);
    }

    /**
     * Évalue un motif {@code glob} sur les chemins indexés, ou {@code Optional.empty()} si l'index ne
     * peut pas répondre (non amorcé, motif invalide). Un résultat présent — même vide — signifie « la
     * réponse vient de l'index » : l'appelant ne doit l'utiliser que lorsque c'est sûr.
     *
     * @param base sous-dossier de base ({@code path} de l'outil), ou vide/null pour tout le projet
     */
    @Transactional(readOnly = true)
    public Optional<String> glob(UUID userId, UUID workspaceId, String pattern, String base) {
        if (userId == null || workspaceId == null || pattern == null || pattern.isBlank()) {
            return Optional.empty();
        }
        Optional<RepoIndexEntry> row = repo.findByUserIdAndWorkspaceId(userId, workspaceId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<String> matched = match(splitPaths(row.get().getPaths()), pattern, base);
            return Optional.of(String.join("\n", matched));
        } catch (RuntimeException ex) {
            // Motif que la gateway ne sait pas évaluer : on laisse le runner répondre (et signaler).
            log.debug("Motif glob non évalué depuis l'index ({})", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Relit et range l'index de ce projet. Hors chemin critique, throttlé, ne lève jamais. N'agit que
     * sur cible {@code RUNNER}.
     */
    public void refresh(UUID userId, Workspace workspace) {
        if (userId == null || workspace == null || !workspace.isRunnerTarget()
                || workspace.getHostId() == null || workspace.getId() == null) {
            return;
        }
        if (!claimRefresh(workspace.getId())) {
            return;
        }
        try {
            RunnerCallResult listed = runnerToolGateway.listFiles(RunnerTargets.of(workspace),
                    UUID.randomUUID().toString());
            if (!listed.ok()) {
                return; // Machine muette : on ne touche à RIEN, l'ancien index reste et sert.
            }
            String content = listed.content() == null ? "" : listed.content();
            List<String> lines = splitPaths(content);
            if (lines.size() > MAX_PATHS || content.length() > MAX_CONTENT_CHARS) {
                // Repo trop gros : on ne sert jamais une liste incomplète. On efface l'index existant
                // (le glob repartira toujours en direct) plutôt que de garder une copie partielle.
                repo.deleteByUserIdAndWorkspaceId(userId, workspace.getId());
                return;
            }
            store(userId, workspace, content, lines.size());
        } catch (RuntimeException ex) {
            log.debug("Index de repo non rafraîchi ({})", ex.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------- internes

    private boolean claimRefresh(UUID workspaceId) {
        Instant now = Instant.now();
        Instant previous = lastRefresh.get(workspaceId);
        if (previous != null && Duration.between(previous, now).compareTo(MIN_INTERVAL) < 0) {
            return false;
        }
        lastRefresh.put(workspaceId, now);
        return true;
    }

    @Transactional
    void store(UUID userId, Workspace workspace, String content, int count) {
        RepoIndexEntry existing =
                repo.findByUserIdAndWorkspaceId(userId, workspace.getId()).orElse(null);
        if (existing != null && content.equals(existing.getPaths())) {
            existing.setObservedAt(OffsetDateTime.now());
            repo.save(existing);
            return;
        }
        RepoIndexEntry entry = existing != null ? existing : RepoIndexEntry.builder()
                .userId(userId).hostId(workspace.getHostId()).workspaceId(workspace.getId()).build();
        entry.setPaths(content);
        entry.setPathCount(count);
        entry.setObservedAt(OffsetDateTime.now());
        repo.save(entry);
    }

    /** Les chemins qui correspondent au motif, dans un ordre stable par chemin. */
    static List<String> match(List<String> paths, String pattern, String base) {
        String root = base == null ? "" : base.strip();
        while (root.startsWith("/")) {
            root = root.substring(1);
        }
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        // Java n'accepte pas qu'un `**/` en tête matche un fichier de la RACINE (« App.java » face à
        // « **/*.java ») ; or les globbers usuels (dont celui du runner) le font. On teste alors AUSSI
        // le motif privé de son `**/` — sinon l'index MANQUERAIT un fichier que le runner trouverait.
        PathMatcher rootMatcher = pattern.startsWith("**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3))
                : null;
        List<String> out = new ArrayList<>();
        for (String path : paths) {
            String relative;
            if (root.isEmpty()) {
                relative = path;
            } else if (path.equals(root)) {
                continue; // Le dossier de base lui-même n'est pas un résultat.
            } else if (path.startsWith(root + "/")) {
                relative = path.substring(root.length() + 1);
            } else {
                continue; // Hors du sous-dossier de base.
            }
            Path candidate = Path.of(relative);
            if (matcher.matches(candidate)
                    || (rootMatcher != null && rootMatcher.matches(candidate))) {
                out.add(path);
            }
        }
        out.sort(java.util.Comparator.naturalOrder());
        return out;
    }

    private static List<String> splitPaths(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(content.split("\n"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }
}
