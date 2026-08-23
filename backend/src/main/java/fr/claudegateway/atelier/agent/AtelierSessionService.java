package fr.claudegateway.atelier.agent;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.agent.ManagedAgentProvider.SessionUsage;
import fr.claudegateway.quota.QuotaService;

/**
 * Orchestration d'un run d'exécution d'atelier sur une session Managed Agents (F-28 / Phase 2,
 * ADR-013, <b>révisé par ADR-014</b>). Réalise le <b>pont fichiers S3⇄session</b> :
 *
 * <ol>
 *   <li>isolation {@code user_id} d'abord ({@link WorkspaceService#requireOwned}) ;</li>
 *   <li><b>session persistante par workspace</b> (F-30 SF-30-04) : ouverte au premier message avec
 *       les fichiers montés, puis <b>réutilisée</b> — la sandbox et son système de fichiers survivent
 *       d'un message à l'autre ({@code npm install} une fois, les tests réutilisent l'installation) ;</li>
 *   <li>message utilisateur, attente de complétion, récupération des sorties ;</li>
 *   <li>réécriture <b>incrémentale</b> des sorties dans le workspace (garde-fous Phase 1) ;</li>
 *   <li>décompte du <b>delta</b> d'usage depuis le relevé précédent (jamais le cumul).</li>
 * </ol>
 *
 * <p>La session n'est plus terminée en {@code finally} : elle est terminée explicitement par
 * {@link #resetSession} (une session {@code idle} n'est pas facturée — ADR-014).</p>
 *
 * <p>Provider Independence : ne dépend que de {@link ManagedAgentProvider} (jamais d'Anthropic).
 * <b>Aucun endpoint exposé</b> (SF-28-10) ; service interne activé par flag
 * ({@code app.atelier.agent.enabled}). Flag off ⇒ refus <b>avant tout appel réseau</b>.</p>
 */
@Service
public class AtelierSessionService {

    private static final Logger log = LoggerFactory.getLogger(AtelierSessionService.class);

    /** Préfixe de montage des fichiers du workspace dans le bac à sable. */
    private static final String WORKSPACE_MOUNT = "/workspace/";

    /** Préfixe possible des sorties générées par la session (retiré à la réécriture). */
    private static final String OUTPUTS_PREFIX = "/mnt/session/outputs/";

    private final ManagedAgentProvider provider;
    private final WorkspaceService workspaceService;
    private final AtelierAgentBootstrapService bootstrapService;
    private final AtelierAgentProperties properties;
    private final QuotaService quotaService;
    private final WorkspaceRepository workspaceRepository;

    /**
     * Sorties déjà rapatriées, par session (F-30 SF-30-04). Une session persistante expose à chaque
     * tour <b>toutes</b> ses sorties, y compris celles des tours précédents : sans ce registre, chaque
     * tour réécrirait tout le workspace et signalerait comme « modifiés » des fichiers intacts.
     *
     * <p>Volontairement en mémoire : un redémarrage d'instance fait au pire réécrire une fois des
     * contenus identiques (idempotent). Le persister n'apporterait que de la complexité.</p>
     */
    private final Map<String, Set<String>> syncedOutputs = new ConcurrentHashMap<>();

    public AtelierSessionService(ManagedAgentProvider provider, WorkspaceService workspaceService,
            AtelierAgentBootstrapService bootstrapService, AtelierAgentProperties properties,
            QuotaService quotaService, WorkspaceRepository workspaceRepository) {
        this.provider = provider;
        this.workspaceService = workspaceService;
        this.bootstrapService = bootstrapService;
        this.properties = properties;
        this.quotaService = quotaService;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * Exécute une tâche d'atelier sur une session Managed Agents éphémère, avec pont fichiers.
     *
     * @param userId      utilisateur propriétaire (isolation)
     * @param workspaceId workspace cible
     * @param message     message/instruction à envoyer à l'agent
     * @return la réponse finale de l'agent + la liste des fichiers réécrits
     * @throws AtelierAgentDisabledException si la Phase 2 est désactivée (aucun appel réseau)
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException si le workspace n'est pas possédé
     */
    public AtelierSessionResult runTask(UUID userId, UUID workspaceId, String message) {
        // Run non-streamé = run streamé avec un écouteur inerte (aucune régression).
        return runTaskStreaming(userId, workspaceId, message, AtelierAgentListener.NOOP);
    }

    /**
     * Variante <b>streaming</b> de {@link #runTask} : exécute le même run (pont fichiers compris) mais
     * relaie en direct chaque étape (texte de l'agent, usage d'outil, transition d'état) au
     * {@code listener} pendant l'attente de complétion. Les garde-fous restent identiques : isolation
     * {@code user_id} d'abord, flag off avant tout appel réseau, terminaison systématique ({@code finally}).
     *
     * @param userId      utilisateur propriétaire (isolation)
     * @param workspaceId workspace cible
     * @param message     message/instruction à envoyer à l'agent
     * @param listener    écouteur des étapes du run (jamais {@code null} ; {@link AtelierAgentListener#NOOP}
     *                    pour ne rien relayer)
     * @return la réponse finale de l'agent + la liste des fichiers réécrits
     * @throws AtelierAgentDisabledException si la Phase 2 est désactivée (aucun appel réseau)
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException si le workspace n'est pas possédé
     */
    public AtelierSessionResult runTaskStreaming(UUID userId, UUID workspaceId, String message,
            AtelierAgentListener listener) {
        AtelierAgentListener sink = listener == null ? AtelierAgentListener.NOOP : listener;

        // 1. Isolation EN PREMIER : workspace d'un autre user / inexistant ⇒ 404, aucun appel provider.
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);

        // 2. Flag off ⇒ refus avant tout appel réseau / coût runtime.
        if (!properties.enabled()) {
            throw new AtelierAgentDisabledException("Atelier Phase 2 désactivé.");
        }

        // 2 bis. Pré-vol quota/plafond (SF-28-12) AVANT toute création OU réutilisation de session :
        // un refus ici n'engage AUCUN coût. Le sandbox est décompté comme les tokens.
        quotaService.assertWithinQuota(userId);
        quotaService.assertWithinSandboxLimit(userId);

        // 3. Config Managed Agents (environment/agent provisionnés une fois).
        AtelierAgentConfig config = bootstrapService.ensureBootstrapped()
                .orElseThrow(() -> new IllegalStateException(
                        "Configuration Managed Agents indisponible (bootstrap requis)."));

        // 4. Pont vers le provider : chaque event relayé est transmis au listener applicatif.
        ManagedEventListener bridge = new ManagedEventListener() {
            @Override
            public void onAgentText(String text) {
                sink.onAgentText(text);
            }

            @Override
            public void onAction(String tool, String detail) {
                sink.onAction(tool, detail);
            }

            @Override
            public void onAction(String tool, String toolUseId, String detail) {
                sink.onAction(tool, toolUseId, detail);
            }

            @Override
            public void onActionResult(String tool, String toolUseId, String output, boolean error) {
                sink.onActionResult(tool, toolUseId, output, error);
            }

            @Override
            public void onStatus(String state) {
                sink.onStatus(state);
            }
        };

        // 5. Session PERSISTANTE (F-30 SF-30-04) : réutilisée si le workspace en porte une, sinon
        // ouverte avec les fichiers montés. Réutiliser sans remonter les fichiers est délibéré : la
        // sandbox porte l'état laissé par le tour précédent, le remontage l'écraserait.
        String sessionId = workspace.getAgentSessionId();
        SessionRun run;
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                run = runInSession(sessionId, message, bridge);
            } catch (RuntimeException ex) {
                // Session expirée, terminée ou inconnue : on en ouvre une neuve et on rejoue le
                // message UNE fois. Boucler au-delà masquerait une panne réelle du fournisseur.
                log.debug("Session d'atelier injouable, ouverture d'une nouvelle session.");
                sessionId = openSession(userId, workspaceId, config, workspace);
                run = runInSession(sessionId, message, bridge);
            }
        } else {
            sessionId = openSession(userId, workspaceId, config, workspace);
            run = runInSession(sessionId, message, bridge);
        }

        // 6. Resync INCRÉMENTAL : une session persistante réexpose ses sorties à chaque tour ; ne
        // réécrire que les nouvelles évite de repasser sur tout le workspace et de signaler comme
        // modifiés des fichiers intacts.
        List<String> changed = new ArrayList<>();
        Set<String> alreadySynced = syncedOutputs.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        Map<String, String> byBasename = basenameIndex(workspaceService.tree(userId, workspaceId));
        Set<String> knownPaths = new HashSet<>(workspaceService.tree(userId, workspaceId));
        for (OutputFile output : provider.listOutputs(sessionId)) {
            if (!alreadySynced.add(output.fileId())) {
                continue;
            }
            byte[] bytes = provider.downloadFile(output.fileId());
            String relPath = resolveOutputPath(normalizePath(output.filename()), knownPaths, byBasename);
            workspaceService.writeFile(userId, workspaceId, relPath, new String(bytes, UTF_8));
            changed.add(relPath);
        }

        // 7. Décompte du DELTA de consommation (F-30 SF-30-04) : `getSessionUsage` renvoie un cumul
        // depuis l'ouverture de la session — recréditer ce cumul à chaque tour ferait payer plusieurs
        // fois la même consommation. Best-effort : la réponse et le resync sont déjà livrés.
        recordSessionUsage(userId, workspaceId, sessionId);

        log.debug("Run atelier terminé : {} fichier(s) modifié(s).", changed.size());
        return new AtelierSessionResult(run.reply(), changed);
    }

    /**
     * Ouvre une session pour ce workspace et persiste son identifiant (F-30 SF-30-04) : téléverse les
     * fichiers du workspace (bornés par {@code maxSessionFiles}) et les monte dans la sandbox. Remet
     * à zéro les compteurs d'usage, une session neuve repartant d'un cumul nul.
     */
    private String openSession(UUID userId, UUID workspaceId, AtelierAgentConfig config, Workspace workspace) {
        List<String> paths = workspaceService.tree(userId, workspaceId);
        int max = properties.maxSessionFiles();
        if (paths.size() > max) {
            paths = paths.subList(0, max);
        }
        List<FileMount> mounts = new ArrayList<>();
        for (String path : paths) {
            String content = workspaceService.readFile(userId, workspaceId, path);
            // La Files API refuse les caractères interdits dans le nom (dont « / ») : on téléverse sous
            // un nom aplati, tandis que l'arborescence réelle est portée par le mount_path.
            String fileId = provider.uploadFile(uploadFilename(path), content.getBytes(UTF_8));
            mounts.add(new FileMount(fileId, WORKSPACE_MOUNT + path));
        }
        ManagedSession session = provider.createSession(config.getAgentId(), config.getEnvironmentId(), mounts);
        workspace.setAgentSessionId(session.id());
        workspace.setAgentSessionStartedAt(OffsetDateTime.now());
        workspace.setAgentInputTokens(0L);
        workspace.setAgentOutputTokens(0L);
        workspace.setAgentActiveSeconds(0L);
        workspaceRepository.save(workspace);
        return session.id();
    }

    /** Envoie le message dans la session donnée et attend la complétion du tour. */
    private SessionRun runInSession(String sessionId, String message, ManagedEventListener bridge) {
        provider.sendUserMessage(sessionId, message);
        return provider.awaitCompletion(sessionId, properties.sessionTimeout(), properties.maxPolls(), bridge);
    }

    /**
     * Termine la session du workspace et efface son identifiant (F-30 SF-30-04) : le message suivant
     * repartira d'une sandbox neuve. Contrepartie de l'abandon du {@code finally} — une sandbox
     * longue-vie détenant l'état d'un projet doit avoir une fin de vie explicite (ADR-014).
     *
     * <p>La terminaison est <b>best-effort</b> : si le fournisseur refuse (session déjà morte,
     * indisponible), l'identifiant est effacé quand même, sinon le workspace resterait collé à une
     * session injouable.</p>
     *
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException si le workspace n'est pas possédé
     */
    public void resetSession(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        String sessionId = workspace.getAgentSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            provider.terminateSession(sessionId);
        } catch (RuntimeException ex) {
            log.debug("Terminaison de session ignorée (best-effort) : identifiant effacé malgré tout.");
        }
        syncedOutputs.remove(sessionId);
        workspace.setAgentSessionId(null);
        workspace.setAgentSessionStartedAt(null);
        workspaceRepository.save(workspace);
    }

    /**
     * Table basename → chemin d'origine : la Files API renvoie les sorties sous leur seul nom de base
     * (l'arborescence est perdue). On remappe une sortie vers son chemin de projet quand ce nom de
     * base est <b>unique</b> dans le workspace, pour réécrire au bon endroit (et non à la racine).
     */
    private static Map<String, String> basenameIndex(List<String> paths) {
        Map<String, String> byBasename = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (String p : paths) {
            String b = basename(p);
            if (byBasename.putIfAbsent(b, p) != null) {
                ambiguous.add(b);
            }
        }
        ambiguous.forEach(byBasename::remove);
        return byBasename;
    }

    /**
     * Décompte best-effort de la consommation, en <b>delta</b> depuis le relevé précédent (F-30
     * SF-30-04). {@code getSessionUsage} renvoie le cumul depuis l'ouverture de la session : sur une
     * session persistante, recréditer ce cumul à chaque tour ferait payer plusieurs fois la même
     * consommation, de plus en plus cher à mesure que la session vit.
     *
     * <p>Le delta est borné à zéro : un relevé inférieur au précédent (session remplacée, compteur
     * remis à zéro côté fournisseur) ne doit jamais créditer de valeur négative.</p>
     *
     * <p>Toute erreur est <b>avalée</b> (log debug) : le run a déjà produit sa réponse et
     * resynchronisé ses fichiers, un comptage manqué ne doit rien interrompre.</p>
     */
    private void recordSessionUsage(UUID userId, UUID workspaceId, String sessionId) {
        try {
            SessionUsage usage = provider.getSessionUsage(sessionId);
            Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
            long inputDelta = Math.max(0L, usage.inputTokens() - workspace.getAgentInputTokens());
            long outputDelta = Math.max(0L, usage.outputTokens() - workspace.getAgentOutputTokens());
            long secondsDelta = Math.max(0L, usage.activeSeconds() - workspace.getAgentActiveSeconds());
            workspace.setAgentInputTokens(usage.inputTokens());
            workspace.setAgentOutputTokens(usage.outputTokens());
            workspace.setAgentActiveSeconds(usage.activeSeconds());
            workspaceRepository.save(workspace);
            // recordUsage prend des int : on borne les deltas à Integer.MAX_VALUE.
            quotaService.recordUsage(userId, (int) Math.min(inputDelta, Integer.MAX_VALUE),
                    (int) Math.min(outputDelta, Integer.MAX_VALUE));
            quotaService.recordSandboxSeconds(userId, secondsDelta);
        } catch (RuntimeException ex) {
            log.debug("Décompte de l'usage de session ignoré (best-effort) : run déjà livré.");
        }
    }

    /**
     * Ramène un nom de fichier de sortie à un chemin relatif au workspace, en retirant un éventuel
     * préfixe de montage ({@code /workspace/}) ou de sorties ({@code /mnt/session/outputs/}).
     */
    private static String normalizePath(String filename) {
        if (filename == null) {
            return "";
        }
        String path = filename;
        if (path.startsWith(OUTPUTS_PREFIX)) {
            path = path.substring(OUTPUTS_PREFIX.length());
        } else if (path.startsWith(WORKSPACE_MOUNT)) {
            path = path.substring(WORKSPACE_MOUNT.length());
        }
        return path;
    }

    /**
     * Nom de fichier « plat » accepté par la Files API : tout caractère hors {@code [A-Za-z0-9._-]}
     * (dont le séparateur de chemin {@code /}) est remplacé par {@code _}. L'arborescence réelle du
     * projet reste portée par le {@code mount_path} de la ressource, pas par ce nom.
     */
    static String uploadFilename(String path) {
        String flat = path.replaceAll("[^A-Za-z0-9._-]", "_");
        return flat.isBlank() ? "file" : flat;
    }

    /** Nom de base d'un chemin (segment après le dernier {@code /}). */
    static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Résout le chemin de réécriture d'une sortie : chemin exact s'il existe déjà dans le workspace ;
     * sinon, chemin d'origine si le nom de base est unique dans le projet (la Files API aplatit les
     * sorties à leur seul nom de base) ; sinon le chemin tel quel (fichier nouveau).
     */
    static String resolveOutputPath(String relPath, Set<String> knownPaths, Map<String, String> byBasename) {
        if (knownPaths.contains(relPath)) {
            return relPath;
        }
        String mapped = byBasename.get(basename(relPath));
        return mapped != null ? mapped : relPath;
    }
}
