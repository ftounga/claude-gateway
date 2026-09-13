package fr.claudegateway.radar;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.RadarSubjectProjectService.ProjectSubjectsView;
import fr.claudegateway.radar.RadarSubjectProjectService.SubjectProjectsView;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * <b>Le lien entre un sujet et un projet</b> (F-106 / SF-106-06) : la page du sujet le lit, le pose et le
 * défait ; la Forge lit, projet par projet, les sujets qui s'y rattachent.
 *
 * <p><b>Droit et isolation</b> : ceux des API de la Vigie — droit Vigie d'abord, puis poste possédé (404) et
 * activé dans la Vigie (409), puis sujet et projet du périmètre (404).</p>
 */
@RestController
@RequestMapping("/radar/hosts/{hostId}")
public class RadarSubjectProjectController {

    private final RadarSubjectProjectService service;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public RadarSubjectProjectController(RadarSubjectProjectService service, RadarScopeResolver scopeResolver,
            TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.service = service;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    /** Les liens du sujet (confirmés et proposés) et les projets du poste qu'on peut lier. */
    @GetMapping("/subjects/{subjectId}/projects")
    public SubjectProjectsView projects(@PathVariable UUID hostId, @PathVariable UUID subjectId) {
        return service.projects(scope(hostId), subjectId);
    }

    /** Lie le sujet au projet, ou confirme la proposition. Idempotent. */
    @PutMapping("/subjects/{subjectId}/projects/{workspaceId}")
    public SubjectProjectsView link(@PathVariable UUID hostId, @PathVariable UUID subjectId,
            @PathVariable UUID workspaceId) {
        return service.link(scope(hostId), subjectId, workspaceId);
    }

    /** Délie, ou refuse la proposition : la paire ne sera plus proposée. Idempotent. */
    @DeleteMapping("/subjects/{subjectId}/projects/{workspaceId}")
    public SubjectProjectsView unlink(@PathVariable UUID hostId, @PathVariable UUID subjectId,
            @PathVariable UUID workspaceId) {
        return service.unlink(scope(hostId), subjectId, workspaceId);
    }

    /** La passerelle de la Forge : les sujets liés, projet par projet. */
    @GetMapping("/project-subjects")
    public List<ProjectSubjectsView> projectSubjects(@PathVariable UUID hostId) {
        return service.subjectsByProject(scope(hostId));
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.requireInVigie(currentUser.requireId(), hostId);
    }
}
