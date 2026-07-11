package fr.claudegateway.atelier.agent;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.WorkspaceService;

/**
 * Orchestration d'un run d'exécution d'atelier sur une session Managed Agents (F-28 / Phase 2,
 * ADR-013). Réalise le <b>pont fichiers S3⇄session</b> en mode non-streamé (polling) :
 *
 * <ol>
 *   <li>isolation {@code user_id} d'abord ({@link WorkspaceService#requireOwned}) ;</li>
 *   <li>montage des fichiers du workspace dans la session (téléversement + {@code mount_path}) ;</li>
 *   <li>message utilisateur, attente de complétion, récupération des sorties ;</li>
 *   <li>réécriture des sorties dans le workspace (garde-fous Phase 1) ;</li>
 *   <li>terminaison <b>systématique</b> de la session ({@code finally}).</li>
 * </ol>
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

    public AtelierSessionService(ManagedAgentProvider provider, WorkspaceService workspaceService,
            AtelierAgentBootstrapService bootstrapService, AtelierAgentProperties properties) {
        this.provider = provider;
        this.workspaceService = workspaceService;
        this.bootstrapService = bootstrapService;
        this.properties = properties;
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
        workspaceService.requireOwned(userId, workspaceId);

        // 2. Flag off ⇒ refus avant tout appel réseau / coût runtime.
        if (!properties.enabled()) {
            throw new AtelierAgentDisabledException("Atelier Phase 2 désactivé.");
        }

        // 3. Config Managed Agents (environment/agent provisionnés une fois).
        AtelierAgentConfig config = bootstrapService.ensureBootstrapped()
                .orElseThrow(() -> new IllegalStateException(
                        "Configuration Managed Agents indisponible (bootstrap requis)."));

        // 4. Montage des fichiers du workspace (bornés) : uniquement le préfixe S3 de l'utilisateur.
        List<String> paths = workspaceService.tree(userId, workspaceId);
        int max = properties.maxSessionFiles();
        if (paths.size() > max) {
            paths = paths.subList(0, max);
        }
        // Table basename → chemin d'origine : la Files API renvoie les sorties sous leur seul nom de
        // base (l'arborescence est perdue). On remappe une sortie vers son chemin de projet quand ce
        // nom de base est unique dans le workspace, pour réécrire au bon endroit (et non à la racine).
        Set<String> knownPaths = new HashSet<>(paths);
        Map<String, String> byBasename = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (String p : paths) {
            String b = basename(p);
            if (byBasename.putIfAbsent(b, p) != null) {
                ambiguous.add(b);
            }
        }
        ambiguous.forEach(byBasename::remove);

        List<FileMount> mounts = new ArrayList<>();
        for (String path : paths) {
            String content = workspaceService.readFile(userId, workspaceId, path);
            // La Files API refuse les caractères interdits dans le nom (dont « / ») : on téléverse sous
            // un nom aplati, tandis que l'arborescence réelle est portée par le mount_path.
            String fileId = provider.uploadFile(uploadFilename(path), content.getBytes(UTF_8));
            mounts.add(new FileMount(fileId, WORKSPACE_MOUNT + path));
        }

        // 5. Session éphémère montant ces fichiers.
        ManagedSession session = provider.createSession(config.getAgentId(), config.getEnvironmentId(), mounts);

        List<String> changed = new ArrayList<>();
        SessionRun run;
        try {
            // 6. Message + attente de complétion + resynchronisation des sorties vers S3.
            provider.sendUserMessage(session.id(), message);
            // Pont vers le provider : chaque event relayé est transmis au listener applicatif.
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
                public void onStatus(String state) {
                    sink.onStatus(state);
                }
            };
            run = provider.awaitCompletion(session.id(), properties.sessionTimeout(), properties.maxPolls(), bridge);
            for (OutputFile output : provider.listOutputs(session.id())) {
                byte[] bytes = provider.downloadFile(output.fileId());
                String relPath = resolveOutputPath(normalizePath(output.filename()), knownPaths, byBasename);
                workspaceService.writeFile(userId, workspaceId, relPath, new String(bytes, UTF_8));
                changed.add(relPath);
            }
        } finally {
            // 7. Terminaison systématique (succès, erreur, timeout) : borne le coût runtime.
            provider.terminateSession(session.id());
        }

        log.debug("Run atelier terminé : {} fichier(s) modifié(s).", changed.size());
        return new AtelierSessionResult(run.reply(), changed);
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
