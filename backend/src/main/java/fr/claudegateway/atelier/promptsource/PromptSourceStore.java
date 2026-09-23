package fr.claudegateway.atelier.promptsource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>Le cache des sources de la consigne</b> : la copie de travail, côté gateway, des fichiers que
 * {@code buildSystemPrompt} relit à <b>chaque</b> message sur le runner (F-148 / SF-148-06).
 *
 * <p><b>Pourquoi une copie.</b> Amorcer la consigne sur le runner coûte jusqu'à ~19 allers-retours
 * avant le premier mot du modèle — {@code CLAUDE.md}, {@code STATE.md}/{@code PLAN-ACTION.md} du
 * sujet, le listage, puis chaque skill du catalogue —, pour un contenu qui ne change presque jamais
 * d'un tour à l'autre. On le relit donc une fois la réponse partie : le tour en cours ne paie rien,
 * le suivant trouve tout prêt. C'est le geste <b>exact</b> de {@link fr.claudegateway.governance.map}
 * ({@code HostMapStore}).</p>
 *
 * <p><b>Rien n'est jamais détruit par un échec.</b> Machine muette, fichier illisible, droits
 * refusés : l'ancienne copie reste et continue de servir.</p>
 *
 * <p><b>Étranglé.</b> Une rafale de tours sur le même projet ne déclenche qu'un rafraîchissement par
 * fenêtre : au-delà, on sait déjà que rien n'a eu le temps de changer.</p>
 *
 * <p><b>Isolation.</b> Toute lecture et toute écriture portent {@code user_id} <b>et</b>
 * {@code workspace_id} ; c'est aussi la clé d'unicité en base. {@code host_id} est rangé pour la
 * purge.</p>
 *
 * <p><b>Cache de prompt (F-134) préservé.</b> Le contenu servi est byte-identique à la lecture
 * directe tant que l'empreinte ne change pas — le préfixe reste donc stable d'un tour à l'autre.</p>
 */
@Service
public class PromptSourceStore {

    private static final Logger log = LoggerFactory.getLogger(PromptSourceStore.class);

    /**
     * Délai minimal entre deux rafraîchissements d'un même projet. Comme pour la carte, il ne protège
     * pas contre la perte d'information (un tour dure bien plus) : il protège la machine du client
     * contre une rafale de lectures quand plusieurs terminaux travaillent en parallèle.
     */
    static final Duration MIN_INTERVAL = Duration.ofSeconds(30);

    /** Borne du contenu gardé par fichier : au-delà, la copie n'est plus une copie. */
    static final int MAX_CONTENT_CHARS = 400_000;

    /**
     * Chemin réservé sous lequel l'arborescence est rangée : une barre oblique en tête, ce qu'un
     * chemin <b>relatif</b> issu de {@code listFiles} n'a jamais — donc aucune collision possible avec
     * un vrai fichier, et aucun octet nul (interdit dans un {@code text} PostgreSQL). Son contenu est
     * le listage brut, une ligne par chemin, tel que {@code safeTree} le découpe.
     */
    static final String TREE_PATH = "/__prompt_source_tree__";

    private final RunnerToolGateway runnerToolGateway;
    private final PromptSourceFileRepository files;

    /** Dernier rafraîchissement par projet — en mémoire : le perdre ne coûte qu'une lecture de plus. */
    private final Map<UUID, Instant> lastRefresh = new ConcurrentHashMap<>();

    public PromptSourceStore(RunnerToolGateway runnerToolGateway, PromptSourceFileRepository files) {
        this.runnerToolGateway = runnerToolGateway;
        this.files = files;
    }

    /** Vrai si le cache de ce projet est amorcé (au moins une ligne rangée). */
    @Transactional(readOnly = true)
    public boolean isPrimed(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return false;
        }
        return files.existsByUserIdAndWorkspaceId(userId, workspaceId);
    }

    /**
     * Contenu rangé d'un fichier, ou vide.
     *
     * <p><b>Absent du cache = traité comme absent de la machine</b> : le vide rendu ici a la même
     * sémantique que celui de {@code readOptional}, si bien que le préfixe est identique à la lecture
     * directe.</p>
     */
    @Transactional(readOnly = true)
    public Optional<String> read(UUID userId, UUID workspaceId, String path) {
        if (userId == null || workspaceId == null || path == null) {
            return Optional.empty();
        }
        return files.findByUserIdAndWorkspaceIdAndPath(userId, workspaceId, path)
                .map(PromptSourceFile::getContent)
                .filter(content -> content != null);
    }

    /** L'arborescence rangée, ligne par ligne, ou une liste vide — au format de {@code safeTree}. */
    @Transactional(readOnly = true)
    public List<String> tree(UUID userId, UUID workspaceId) {
        return read(userId, workspaceId, TREE_PATH)
                .map(content -> content.isEmpty() ? List.<String>of() : List.of(content.split("\n")))
                .orElseGet(List::of);
    }

    /**
     * Relit les sources de la consigne de ce projet et les range.
     *
     * <p>Appelée <b>hors du chemin critique</b> d'un tour. Ne lève jamais : un cache en panne doit
     * coûter une consigne un peu ancienne, jamais un tour raté. N'agit que sur cible {@code RUNNER} —
     * en {@code SANDBOX}, les fichiers vivent dans le stockage objet et ne coûtent aucun aller-retour.</p>
     *
     * @param coreFiles      fichiers toujours relus ({@code CLAUDE.md}, {@code STATE.md}, {@code PLAN-ACTION.md})
     * @param skillPathFilter prédicat retenant les chemins de skills dans l'arborescence
     * @param maxSkills      plafond de skills rangés (aligné sur le catalogue annoncé)
     */
    public void refresh(UUID userId, Workspace workspace, List<String> coreFiles,
            Predicate<String> skillPathFilter, int maxSkills) {
        if (userId == null || workspace == null || !workspace.isRunnerTarget()
                || workspace.getHostId() == null || workspace.getId() == null) {
            return;
        }
        if (!claimRefresh(workspace.getId())) {
            return;
        }
        RunnerTarget target = RunnerTargets.of(workspace);

        // 1) L'arborescence, d'abord : elle sert au catalogue de skills et se range comme une ligne.
        List<String> tree = List.of();
        try {
            RunnerCallResult listed = runnerToolGateway.listFiles(target, UUID.randomUUID().toString());
            if (listed.ok()) {
                String content = listed.content() == null ? "" : listed.content();
                store(userId, workspace, TREE_PATH, content);
                tree = content.isEmpty() ? List.of() : List.of(content.split("\n"));
            }
            // Pas ok = machine muette : on ne touche à RIEN, la copie précédente reste et sert.
        } catch (RuntimeException ex) {
            log.debug("Arborescence non rafraîchie ({})", ex.getClass().getSimpleName());
        }

        // 2) Les fichiers cœur, puis les skills du catalogue (bornés).
        List<String> paths = new ArrayList<>(coreFiles == null ? List.of() : coreFiles);
        if (skillPathFilter != null && maxSkills > 0) {
            tree.stream().filter(skillPathFilter).limit(maxSkills).forEach(paths::add);
        }
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                RunnerCallResult read = runnerToolGateway.readFile(target,
                        UUID.randomUUID().toString(), path);
                if (read.ok()) {
                    store(userId, workspace, path, read.content() == null ? "" : read.content());
                }
                // Pas ok = absent ou illisible : on ne range rien, on ne détruit rien.
            } catch (RuntimeException ex) {
                log.debug("Source de consigne non lue ({})", ex.getClass().getSimpleName());
            }
        }
    }

    // -------------------------------------------------------------- internes

    /**
     * Vrai si l'on prend la main pour rafraîchir ce projet maintenant. Le jeton est posé <b>avant</b>
     * la lecture : deux tours qui finissent en même temps ne doivent pas partir tous les deux.
     */
    private boolean claimRefresh(UUID workspaceId) {
        Instant now = Instant.now();
        Instant previous = lastRefresh.get(workspaceId);
        if (previous != null && Duration.between(previous, now).compareTo(MIN_INTERVAL) < 0) {
            return false;
        }
        lastRefresh.put(workspaceId, now);
        return true;
    }

    /**
     * Range un fichier.
     *
     * <p>Un contenu inchangé (même empreinte) n'est pas réécrit : seule {@code observed_at} bouge.
     * C'est ce qui garantit un préfixe byte-stable tant que la source ne change pas.</p>
     */
    void store(UUID userId, Workspace workspace, String path, String rawContent) {
        String content = rawContent.length() > MAX_CONTENT_CHARS
                ? rawContent.substring(0, MAX_CONTENT_CHARS)
                : rawContent;
        String digest = digestOf(content);
        PromptSourceFile existing = files
                .findByUserIdAndWorkspaceIdAndPath(userId, workspace.getId(), path).orElse(null);
        if (existing != null && digest.equals(existing.getDigest())) {
            existing.setObservedAt(OffsetDateTime.now());
            files.save(existing);
            return;
        }
        PromptSourceFile file = existing != null ? existing : PromptSourceFile.builder()
                .userId(userId).hostId(workspace.getHostId()).workspaceId(workspace.getId())
                .path(path).build();
        file.setContent(content);
        file.setDigest(digest);
        file.setObservedAt(OffsetDateTime.now());
        files.save(file);
    }

    private static String digestOf(String content) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 est requis par la plateforme : ce chemin n'existe pas en pratique. On rend une
            // empreinte qui ne collera jamais, ce qui force la réécriture — jamais un silence.
            return String.valueOf(content.hashCode());
        }
    }
}
