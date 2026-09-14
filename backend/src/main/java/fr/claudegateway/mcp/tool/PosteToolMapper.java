package fr.claudegateway.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fr.claudegateway.runner.host.dto.RunnerHostOverviewResponse;
import fr.claudegateway.runner.host.dto.RunnerHostOverviewResponse.HostProjectSummary;

/**
 * Projections plates des vues de postes vers le contenu structuré des outils MCP (F-112 / SF-112-04).
 *
 * <p>On ne renvoie que des <b>métadonnées d'état</b> — jamais de contenu de terminal, de commande ou
 * de secret. L'aperçu d'un terminal (« ce que fait le projet ») est un contenu potentiellement tiers :
 * il n'est pas déversé ici ; les outils Terminaux (SF-112-05) le rendent, marqué non fiable.</p>
 */
final class PosteToolMapper {

    private PosteToolMapper() {
    }

    /** Un poste vu depuis la liste ou le détail : identité, état, espaces, mise à jour du runner. */
    static Map<String, Object> host(RunnerHostOverviewResponse host) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", host.id() == null ? null : host.id().toString());
        map.put("name", host.name());
        map.put("virtual", host.virtual());
        map.put("os", host.os());
        map.put("shell", host.shell());
        map.put("runner_version", host.runnerVersion());
        map.put("connected", host.connected());
        map.put("mission_status", host.missionStatus() == null ? null : host.missionStatus().name());
        map.put("last_seen_at", host.lastSeenAt() == null ? null : host.lastSeenAt().toString());
        map.put("last_activity_at",
                host.lastActivityAt() == null ? null : host.lastActivityAt().toString());
        map.put("active_projects", host.activeProjects());
        map.put("live_terminals", host.liveTerminals());
        map.put("spaces", host.spaces() == null ? List.of() : host.spaces());
        map.put("runner_update",
                host.runnerUpdate() == null ? null : host.runnerUpdate().status());
        map.put("runner_update_required",
                host.runnerUpdate() != null && host.runnerUpdate().required());
        map.put("project_count", host.projects() == null ? 0 : host.projects().size());
        return map;
    }

    /** Un poste avec ses projets rangés dessous (détail). */
    static Map<String, Object> hostWithProjects(RunnerHostOverviewResponse host) {
        Map<String, Object> map = host(host);
        map.put("projects", (host.projects() == null ? List.<HostProjectSummary>of() : host.projects())
                .stream().map(PosteToolMapper::project).toList());
        return map;
    }

    /** Un projet vu depuis son poste : identité, cible d'exécution, activité (sans contenu). */
    static Map<String, Object> project(HostProjectSummary project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.id() == null ? null : project.id().toString());
        map.put("name", project.name());
        map.put("project_path", project.projectPath());
        map.put("execution_target", project.executionTarget());
        map.put("last_activity_at",
                project.lastActivityAt() == null ? null : project.lastActivityAt().toString());
        map.put("last_tool", project.lastTool());
        map.put("calls", project.calls());
        map.put("active", project.active());
        map.put("live_terminal", project.liveTerminal());
        return map;
    }
}
