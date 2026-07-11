package fr.claudegateway.atelier.agent;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.List;
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
        List<FileMount> mounts = new ArrayList<>();
        for (String path : paths) {
            String content = workspaceService.readFile(userId, workspaceId, path);
            String fileId = provider.uploadFile(path, content.getBytes(UTF_8));
            mounts.add(new FileMount(fileId, WORKSPACE_MOUNT + path));
        }

        // 5. Session éphémère montant ces fichiers.
        ManagedSession session = provider.createSession(config.getAgentId(), config.getEnvironmentId(), mounts);

        List<String> changed = new ArrayList<>();
        SessionRun run;
        try {
            // 6. Message + attente de complétion + resynchronisation des sorties vers S3.
            provider.sendUserMessage(session.id(), message);
            run = provider.awaitCompletion(session.id(), properties.sessionTimeout(), properties.maxPolls());
            for (OutputFile output : provider.listOutputs(session.id())) {
                byte[] bytes = provider.downloadFile(output.fileId());
                String relPath = normalizePath(output.filename());
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
}
