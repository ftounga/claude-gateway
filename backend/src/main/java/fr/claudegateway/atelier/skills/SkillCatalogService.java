package fr.claudegateway.atelier.skills;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
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
 * <b>Mes skills au bout du {@code /}</b> (F-177 / SF-177-03, décision D5).
 *
 * <p>Le catalogue des skills d'un terminal : ceux du <b>sujet</b> (son arborescence, servie depuis le
 * cache de consigne quand il est amorcé) puis ceux du <b>poste</b> ({@code .claude/skills/} à la racine,
 * listé directement — jamais déduit de l'arborescence récursive et tronquée de la racine). Un skill du
 * sujet masque celui du poste de même nom.</p>
 *
 * <p>{@link #load} rend le contenu d'un skill <b>de façon déterministe</b> : c'est ce qui sert
 * l'invocation {@code /nom texte} (le contenu est joint à la consigne du tour par la gateway, sans
 * dépendre du bon vouloir du modèle) et l'outil {@code skill(nom)}.</p>
 *
 * <p><b>Isolation</b> : le projet est toujours l'entité possédée ({@code requireOwned}) ; la racine
 * lue est celle du poste <b>de ce projet</b>. Le contenu n'est jamais journalisé.</p>
 */
@Service
public class SkillCatalogService {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalogService.class);

    /** Préfixes des skills dans un projet — les mêmes que le catalogue de la consigne. */
    static final List<String> SKILL_PREFIXES = List.of(".claude/skills/", "skills/");
    static final String HOST_SKILLS_DIR = ".claude/skills";
    /** Borne du catalogue rendu. */
    static final int MAX_SKILLS = 60;
    /** Lectures directes de descriptions autorisées par catalogue (le reste vient du cache, ou rien). */
    static final int MAX_LIVE_DESCRIPTION_READS = 20;
    static final int DESCRIPTION_CHARS = 160;
    /** Borne d'un skill chargé. */
    public static final int MAX_SKILL_CHARS = 20_000;

    /** {@code /nom} en tête de message, suivi d'un blanc ou de la fin. */
    private static final Pattern INVOCATION = Pattern.compile("^/([A-Za-z0-9][A-Za-z0-9_-]{0,63})(?:\\s+([\\s\\S]*))?$");

    private final WorkspaceService workspaceService;
    private final RunnerToolGateway gateway;
    private PromptSourceStore promptSourceStore;

    public SkillCatalogService(WorkspaceService workspaceService, RunnerToolGateway gateway) {
        this.workspaceService = workspaceService;
        this.gateway = gateway;
    }

    @Autowired(required = false)
    public void setPromptSourceStore(PromptSourceStore promptSourceStore) {
        this.promptSourceStore = promptSourceStore;
    }

    /** Le catalogue d'un terminal possédé (contrôleur). */
    public List<SkillEntry> catalogOf(UUID userId, UUID workspaceId) {
        return catalog(userId, workspaceService.requireOwned(userId, workspaceId));
    }

    /** Le catalogue : sujet d'abord, puis poste ; borné ; ne lève jamais pour une machine muette. */
    public List<SkillEntry> catalog(UUID userId, Workspace workspace) {
        Map<String, SkillEntry> byName = new LinkedHashMap<>();
        int[] liveReads = {0};
        if (!workspace.isHostTerminal() && !workspace.isTeamsTerminal()) {
            for (String path : subjectTree(userId, workspace)) {
                String name = nameOf(path);
                if (name == null || byName.containsKey(name) || byName.size() >= MAX_SKILLS) {
                    continue;
                }
                byName.put(name, new SkillEntry(name, path,
                        describe(subjectDescriptionSource(userId, workspace, path, liveReads)), SkillEntry.SUJET));
            }
        }
        if (workspace.getHostId() != null && workspace.isRunnerTarget() && !workspace.isTeamsTerminal()) {
            for (String path : hostSkillPaths(workspace)) {
                String name = nameOf(path);
                if (name == null || byName.containsKey(name) || byName.size() >= MAX_SKILLS) {
                    continue;
                }
                String body = null;
                if (liveReads[0] < MAX_LIVE_DESCRIPTION_READS) {
                    liveReads[0]++;
                    body = readHost(workspace, path).orElse(null);
                }
                byName.put(name, new SkillEntry(name, path, describe(body), SkillEntry.POSTE));
            }
        }
        return List.copyOf(byName.values());
    }

    /** Le skill {@code name} et son contenu (borné), ou vide s'il n'existe pas. */
    public Optional<LoadedSkill> load(UUID userId, Workspace workspace, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.strip().toLowerCase(Locale.ROOT);
        for (SkillEntry entry : catalog(userId, workspace)) {
            if (entry.name().equals(key)) {
                Optional<String> body = SkillEntry.POSTE.equals(entry.origin())
                        ? readHost(workspace, entry.path())
                        : readSubject(userId, workspace, entry.path());
                return body.map(content -> new LoadedSkill(entry, content.length() > MAX_SKILL_CHARS
                        ? content.substring(0, MAX_SKILL_CHARS) + "\n… (skill tronqué)\n" : content));
            }
        }
        return Optional.empty();
    }

    /**
     * Le nom invoqué en tête d'un message ({@code /ticket-jira DECPB-200} → {@code ticket-jira}) et la
     * suite, ou vide si le message ne commence pas par {@code /nom}.
     */
    public static Optional<Invocation> invocationOf(String userText) {
        if (userText == null) {
            return Optional.empty();
        }
        Matcher m = INVOCATION.matcher(userText.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Invocation(m.group(1).toLowerCase(Locale.ROOT),
                m.group(2) == null ? "" : m.group(2).strip()));
    }

    /** Le nom d'un skill d'après son chemin, ou {@code null} si ce n'est pas un skill. */
    static String nameOf(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String path = rawPath.strip().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        String rest = null;
        for (String prefix : SKILL_PREFIXES) {
            if (path.startsWith(prefix)) {
                rest = path.substring(prefix.length());
                break;
            }
        }
        if (rest == null || !rest.toLowerCase(Locale.ROOT).endsWith(".md")) {
            return null;
        }
        String[] parts = rest.split("/");
        String name;
        if (parts.length == 1) {
            name = parts[0].substring(0, parts[0].length() - 3);
        } else if (parts.length == 2 && parts[1].equalsIgnoreCase("SKILL.md")) {
            name = parts[0];
        } else {
            return null;
        }
        name = name.toLowerCase(Locale.ROOT);
        return name.matches("[a-z0-9][a-z0-9_-]{0,63}") ? name : null;
    }

    /** La description : en-tête {@code description:}, sinon la première ligne de texte. */
    static String describe(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String found = null;
        boolean inFront = false;
        for (String raw : body.lines().toList()) {
            String line = raw.strip();
            if (line.equals("---")) {
                inFront = !inFront;
                continue;
            }
            if (inFront) {
                if (line.toLowerCase(Locale.ROOT).startsWith("description:")) {
                    found = line.substring("description:".length()).strip();
                    break;
                }
                continue;
            }
            if (!line.isEmpty() && !line.startsWith("#")) {
                found = line;
                break;
            }
        }
        if (found == null) {
            return "";
        }
        return found.length() > DESCRIPTION_CHARS ? found.substring(0, DESCRIPTION_CHARS) + "…" : found;
    }

    // ------------------------------------------------------------------ lectures

    private List<String> subjectTree(UUID userId, Workspace workspace) {
        try {
            if (!workspace.isRunnerTarget()) {
                return workspaceService.tree(userId, workspace.getId());
            }
            if (promptSourceStore != null && promptSourceStore.isPrimed(userId, workspace.getId())) {
                return promptSourceStore.tree(userId, workspace.getId());
            }
            RunnerCallResult listed = gateway.listFiles(RunnerTargets.of(workspace), UUID.randomUUID().toString());
            return listed != null && listed.ok() && listed.content() != null && !listed.content().isEmpty()
                    ? List.of(listed.content().split("\n")) : List.of();
        } catch (RuntimeException ex) {
            log.debug("Skills du sujet non listés ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private String subjectDescriptionSource(UUID userId, Workspace workspace, String path, int[] liveReads) {
        if (workspace.isRunnerTarget() && promptSourceStore != null) {
            Optional<String> cached = promptSourceStore.read(userId, workspace.getId(), path);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        if (liveReads[0] >= MAX_LIVE_DESCRIPTION_READS) {
            return null;
        }
        liveReads[0]++;
        return readSubject(userId, workspace, path).orElse(null);
    }

    /** Les chemins des skills à la racine du poste ({@code .claude/skills/…}), relatifs à la racine. */
    private List<String> hostSkillPaths(Workspace workspace) {
        try {
            RunnerCallResult listed = gateway.listFiles(
                    new RunnerTarget(workspace.getHostId(), workspace.getId(), HOST_SKILLS_DIR), UUID.randomUUID().toString());
            if (listed == null || !listed.ok() || listed.content() == null || listed.content().isEmpty()) {
                return List.of();
            }
            List<String> paths = new ArrayList<>();
            for (String line : listed.content().split("\n")) {
                String rel = line.strip().replace('\\', '/');
                while (rel.startsWith("./")) {
                    rel = rel.substring(2);
                }
                if (!rel.isEmpty()) {
                    paths.add(HOST_SKILLS_DIR + "/" + rel);
                }
            }
            return paths;
        } catch (RuntimeException ex) {
            log.debug("Skills du poste non listés ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private Optional<String> readSubject(UUID userId, Workspace workspace, String path) {
        try {
            if (!workspace.isRunnerTarget()) {
                return Optional.ofNullable(workspaceService.readFile(userId, workspace.getId(), path));
            }
            RunnerCallResult read = gateway.readFile(RunnerTargets.of(workspace), UUID.randomUUID().toString(), path);
            return read != null && read.ok() ? Optional.ofNullable(read.content()) : Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Optional<String> readHost(Workspace workspace, String path) {
        try {
            RunnerCallResult read = gateway.readFile(new RunnerTarget(workspace.getHostId(), workspace.getId(), ""),
                    UUID.randomUUID().toString(), path);
            return read != null && read.ok() ? Optional.ofNullable(read.content()) : Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** Un skill chargé. */
    public record LoadedSkill(SkillEntry entry, String content) {
    }

    /** Une invocation {@code /nom suite}. */
    public record Invocation(String name, String rest) {
    }
}
