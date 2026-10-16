package fr.claudegateway.atelier.agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.promptsource.PromptSourceStore;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>Les agents nommés d'un terminal</b> (F-182 / SF-182-01, décision D1) : ceux du <b>sujet</b>
 * ({@code .claude/agents/} du projet) puis ceux du <b>poste</b> ({@code .claude/agents/} à la racine) — un
 * agent du sujet masque celui du poste de même nom, comme pour les skills (F-177).
 *
 * <p><b>Frugal</b> : sur cible RUNNER amorcée, tout est servi par le cache de consigne
 * ({@link PromptSourceStore}) — l'arborescence, les fichiers d'agents du sujet et l'index des agents du
 * poste, rangés par le rafraîchissement post-tour. On ne lit en direct que ce que le cache n'a jamais vu,
 * borné.</p>
 *
 * <p><b>Isolation</b> : le projet est toujours l'entité possédée ({@code requireOwned} pour l'API) ; la
 * racine lue est celle du poste <b>de ce projet</b> ; le cache est lu sous {@code (user_id, workspace_id)}.
 * Le contenu des agents n'est jamais journalisé.</p>
 */
@Service
public class NamedAgentCatalogService {

    private static final Logger log = LoggerFactory.getLogger(NamedAgentCatalogService.class);

    /** Lectures directes autorisées par catalogue (le reste vient du cache, ou rien). */
    static final int MAX_LIVE_READS = 20;

    /** Un nom d'agent valide (celui du fichier, en minuscules). */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final WorkspaceService workspaceService;
    private final RunnerToolGateway gateway;
    private PromptSourceStore promptSourceStore;

    public NamedAgentCatalogService(WorkspaceService workspaceService, RunnerToolGateway gateway) {
        this.workspaceService = workspaceService;
        this.gateway = gateway;
    }

    @Autowired(required = false)
    public void setPromptSourceStore(PromptSourceStore promptSourceStore) {
        this.promptSourceStore = promptSourceStore;
    }

    /** Le catalogue d'un terminal possédé (contrôleur) : sans les consignes. */
    public List<NamedAgentEntry> catalogOf(UUID userId, UUID workspaceId) {
        return catalog(userId, workspaceService.requireOwned(userId, workspaceId)).stream()
                .map(NamedAgent::entry).toList();
    }

    /**
     * Les agents du terminal, validés : sujet d'abord, puis poste ; bornés ; ne lève jamais pour une
     * machine muette. Les agents inutilisables y figurent, avec leurs erreurs (l'écran les montre).
     */
    public List<NamedAgent> catalog(UUID userId, Workspace workspace) {
        Map<String, NamedAgent> byName = new LinkedHashMap<>();
        if (workspace == null || workspace.isTeamsTerminal()) {
            return List.of();
        }
        int[] liveReads = {0};
        if (!workspace.isHostTerminal()) {
            int count = 0;
            for (String path : subjectTree(userId, workspace)) {
                String name = nameOf(path);
                if (name == null || byName.containsKey(name) || count >= PromptSourceStore.MAX_AGENT_FILES) {
                    continue;
                }
                count++;
                Optional<String> body = subjectContent(userId, workspace, path, liveReads);
                body.ifPresent(content -> byName.put(name,
                        NamedAgentParser.parse(name, path, NamedAgent.SUJET, content)));
            }
        }
        if (workspace.getHostId() != null && workspace.isRunnerTarget()) {
            for (Map.Entry<String, String> host : hostAgents(userId, workspace, liveReads).entrySet()) {
                String name = nameOf(host.getKey());
                if (name == null || byName.containsKey(name)) {
                    continue;
                }
                byName.put(name, NamedAgentParser.parse(name, host.getKey(), NamedAgent.POSTE, host.getValue()));
            }
        }
        return List.copyOf(byName.values());
    }

    /** L'agent {@code name} (utilisable ou non), ou vide s'il n'existe pas. */
    public Optional<NamedAgent> load(UUID userId, Workspace workspace, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.strip().replaceFirst("^/", "").toLowerCase(Locale.ROOT);
        return catalog(userId, workspace).stream().filter(agent -> agent.name().equals(key)).findFirst();
    }

    /** Le nom d'un agent d'après son chemin, ou {@code null} si ce n'est pas un agent. */
    public static String nameOf(String rawPath) {
        if (!PromptSourceStore.isAgentPath(rawPath)) {
            return null;
        }
        String path = rawPath.strip().replace('\\', '/');
        String file = path.substring(path.lastIndexOf('/') + 1);
        String name = file.substring(0, file.length() - 3).toLowerCase(Locale.ROOT);
        return NAME.matcher(name).matches() ? name : null;
    }

    // ------------------------------------------------------------------ lectures

    private boolean primed(UUID userId, Workspace workspace) {
        try {
            return workspace.isRunnerTarget() && promptSourceStore != null
                    && promptSourceStore.isPrimed(userId, workspace.getId());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private List<String> subjectTree(UUID userId, Workspace workspace) {
        try {
            if (!workspace.isRunnerTarget()) {
                return workspaceService.tree(userId, workspace.getId());
            }
            if (primed(userId, workspace)) {
                return promptSourceStore.tree(userId, workspace.getId());
            }
            RunnerCallResult listed = gateway.listFiles(RunnerTargets.of(workspace), UUID.randomUUID().toString());
            return listed != null && listed.ok() && listed.content() != null && !listed.content().isEmpty()
                    ? List.of(listed.content().split("\n")) : List.of();
        } catch (RuntimeException ex) {
            log.debug("Agents du sujet non listés ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private Optional<String> subjectContent(UUID userId, Workspace workspace, String path, int[] liveReads) {
        if (primed(userId, workspace)) {
            try {
                Optional<String> cached = promptSourceStore.read(userId, workspace.getId(), path.strip());
                if (cached.isPresent()) {
                    return cached;
                }
            } catch (RuntimeException ex) {
                log.debug("Agent du sujet non lu en cache ({})", ex.getClass().getSimpleName());
            }
        }
        if (liveReads[0] >= MAX_LIVE_READS) {
            return Optional.empty();
        }
        liveReads[0]++;
        try {
            if (!workspace.isRunnerTarget()) {
                return Optional.ofNullable(workspaceService.readFile(userId, workspace.getId(), path.strip()));
            }
            RunnerCallResult read = gateway.readFile(RunnerTargets.of(workspace), UUID.randomUUID().toString(),
                    path.strip());
            return read != null && read.ok() ? Optional.ofNullable(read.content()) : Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** Les agents de la racine du poste (chemin → contenu) : le cache s'il a déjà été relu, sinon en direct. */
    private Map<String, String> hostAgents(UUID userId, Workspace workspace, int[] liveReads) {
        Map<String, String> agents = new LinkedHashMap<>();
        if (promptSourceStore != null) {
            try {
                Optional<List<String>> index = promptSourceStore.hostAgentPaths(userId, workspace.getId());
                if (index.isPresent()) {
                    for (String path : index.get()) {
                        promptSourceStore.readHostAgent(userId, workspace.getId(), path)
                                .ifPresent(content -> agents.put(path, content));
                    }
                    return agents;
                }
            } catch (RuntimeException ex) {
                log.debug("Agents du poste non lus en cache ({})", ex.getClass().getSimpleName());
            }
        }
        for (String path : hostAgentPathsLive(workspace)) {
            if (liveReads[0] >= MAX_LIVE_READS) {
                break;
            }
            liveReads[0]++;
            try {
                RunnerCallResult read = gateway.readFile(new RunnerTarget(workspace.getHostId(), workspace.getId(), ""),
                        UUID.randomUUID().toString(), path);
                if (read != null && read.ok() && read.content() != null) {
                    agents.put(path, read.content());
                }
            } catch (RuntimeException ex) {
                log.debug("Agent du poste non lu ({})", ex.getClass().getSimpleName());
            }
        }
        return agents;
    }

    private List<String> hostAgentPathsLive(Workspace workspace) {
        try {
            String dir = PromptSourceStore.AGENTS_DIR.substring(0, PromptSourceStore.AGENTS_DIR.length() - 1);
            RunnerCallResult listed = gateway.listFiles(new RunnerTarget(workspace.getHostId(), workspace.getId(), dir),
                    UUID.randomUUID().toString());
            if (listed == null || !listed.ok() || listed.content() == null || listed.content().isEmpty()) {
                return List.of();
            }
            List<String> paths = new ArrayList<>();
            for (String line : listed.content().split("\n")) {
                String rel = line.strip().replace('\\', '/');
                while (rel.startsWith("./")) {
                    rel = rel.substring(2);
                }
                String path = PromptSourceStore.AGENTS_DIR + rel;
                if (PromptSourceStore.isAgentPath(path) && paths.size() < PromptSourceStore.MAX_AGENT_FILES) {
                    paths.add(path);
                }
            }
            return paths;
        } catch (RuntimeException ex) {
            log.debug("Agents du poste non listés ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }
}
