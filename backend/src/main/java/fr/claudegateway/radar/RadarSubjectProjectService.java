package fr.claudegateway.radar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.host.ClientSpace;
import fr.claudegateway.runner.host.HostSpaceService;

/**
 * <b>Le lien entre un sujet et un projet</b> (F-106 / SF-106-06) : déclaré par l'utilisateur, ou proposé par
 * l'analyse puis confirmé d'un clic.
 *
 * <p><b>Isolation.</b> Tout passe par un {@link RadarScope} (utilisateur et poste) ; les projets sont ceux
 * que {@link WorkspaceService#listByHost} rend pour ce couple — terminaux exclus. Un projet d'un autre poste,
 * d'un autre compte ou supprimé n'est ni lié ni montré : il est « introuvable ».</p>
 *
 * <p><b>Délier, c'est refuser</b> : la ligne passe {@link RadarSubjectProjectState#REFUSED} et reste, pour
 * que l'analyse ne repropose jamais la paire. L'utilisateur peut toujours relier à la main.</p>
 */
@Service
@Transactional
public class RadarSubjectProjectService {

    private final RadarRegistry registry;
    private final RadarSubjectProjectRepository links;
    private final RadarSubjectRepository subjects;
    private final WorkspaceService workspaceService;
    private final HostSpaceService spaceService;

    public RadarSubjectProjectService(RadarRegistry registry, RadarSubjectProjectRepository links,
            RadarSubjectRepository subjects, WorkspaceService workspaceService, HostSpaceService spaceService) {
        this.registry = registry;
        this.links = links;
        this.subjects = subjects;
        this.workspaceService = workspaceService;
        this.spaceService = spaceService;
    }

    /** Un lien affiché sur la page du sujet. */
    public record ProjectLinkView(UUID workspaceId, String name, String projectPath,
            RadarSubjectProjectOrigin origin, RadarSubjectProjectState state) {
    }

    /** Un projet du poste qu'on peut lier. */
    public record ProjectCandidateView(UUID workspaceId, String name, String projectPath) {
    }

    /** Ce que la page du sujet montre : les liens (confirmés et proposés) et les projets liables. */
    public record SubjectProjectsView(boolean inForge, List<ProjectLinkView> links,
            List<ProjectCandidateView> candidates) {
    }

    /** Un sujet nommé, pour la passerelle de la Forge. */
    public record SubjectRef(UUID id, String name) {
    }

    /** Les sujets liés à un projet. */
    public record ProjectSubjectsView(UUID workspaceId, List<SubjectRef> subjects) {
    }

    /** Les liens et les candidats d'un sujet. */
    @Transactional(readOnly = true)
    public SubjectProjectsView projects(RadarScope scope, UUID subjectId) {
        RadarSubject subject = registry.requireSubject(scope, subjectId);
        return view(scope, subject.getId());
    }

    /**
     * Lie le sujet au projet : une déclaration de l'utilisateur, souveraine. Une proposition devient un lien
     * confirmé (son origine est gardée) ; un refus est levé.
     *
     * @throws RadarNotFoundException       sujet ou projet hors du périmètre
     * @throws RadarSubjectMergedException  sujet fusionné : c'est la cible qu'on lie
     */
    public SubjectProjectsView link(RadarScope scope, UUID subjectId, UUID workspaceId) {
        RadarSubject subject = registry.requireSubject(scope, subjectId);
        if (subject.getMergedIntoId() != null) {
            throw new RadarSubjectMergedException("Ce sujet a été fusionné : liez le sujet cible.");
        }
        requireProject(scope, workspaceId);
        Optional<RadarSubjectProject> existing = links.findByUserIdAndHostIdAndSubjectIdAndWorkspaceId(
                scope.userId(), scope.hostId(), subject.getId(), workspaceId);
        if (existing.isEmpty()) {
            links.save(RadarSubjectProject.builder().userId(scope.userId()).hostId(scope.hostId())
                    .subjectId(subject.getId()).workspaceId(workspaceId)
                    .origin(RadarSubjectProjectOrigin.USER).state(RadarSubjectProjectState.CONFIRMED).build());
        } else {
            RadarSubjectProject row = existing.get();
            if (row.getState() == RadarSubjectProjectState.REFUSED) {
                row.setOrigin(RadarSubjectProjectOrigin.USER);
            }
            row.setState(RadarSubjectProjectState.CONFIRMED);
        }
        return view(scope, subject.getId());
    }

    /**
     * Délie le sujet du projet, ou refuse la proposition : la paire est retenue comme refusée. Sans lien, rien
     * ne change.
     *
     * @throws RadarNotFoundException sujet ou projet hors du périmètre
     */
    public SubjectProjectsView unlink(RadarScope scope, UUID subjectId, UUID workspaceId) {
        RadarSubject subject = registry.requireSubject(scope, subjectId);
        requireProject(scope, workspaceId);
        links.findByUserIdAndHostIdAndSubjectIdAndWorkspaceId(scope.userId(), scope.hostId(), subject.getId(),
                workspaceId).ifPresent(row -> row.setState(RadarSubjectProjectState.REFUSED));
        return view(scope, subject.getId());
    }

    /**
     * <b>Propose</b> le lien — seulement si la paire n'a jamais été vue. Un lien confirmé reste confirmé, un
     * refus reste un refus. Réservé à l'analyse, qui a déjà vérifié le projet dans le poste.
     *
     * @return {@code true} si une proposition a été créée
     */
    public boolean propose(RadarScope scope, UUID subjectId, UUID workspaceId) {
        if (links.findByUserIdAndHostIdAndSubjectIdAndWorkspaceId(scope.userId(), scope.hostId(), subjectId,
                workspaceId).isPresent()) {
            return false;
        }
        links.save(RadarSubjectProject.builder().userId(scope.userId()).hostId(scope.hostId())
                .subjectId(subjectId).workspaceId(workspaceId)
                .origin(RadarSubjectProjectOrigin.PROPOSED).state(RadarSubjectProjectState.PROPOSED).build());
        return true;
    }

    /**
     * <b>La passerelle de la Forge</b> : pour chaque projet du poste, les sujets qui lui sont liés (liens
     * confirmés, sujets non fusionnés). Les projets sans sujet ne figurent pas.
     */
    @Transactional(readOnly = true)
    public List<ProjectSubjectsView> subjectsByProject(RadarScope scope) {
        Set<UUID> projectIds = projectsOf(scope).stream().map(Workspace::getId).collect(Collectors.toSet());
        List<RadarSubjectProject> confirmed = links.findByUserIdAndHostIdAndState(scope.userId(), scope.hostId(),
                RadarSubjectProjectState.CONFIRMED).stream()
                .filter(row -> projectIds.contains(row.getWorkspaceId()))
                .toList();
        if (confirmed.isEmpty()) {
            return List.of();
        }
        Map<UUID, RadarSubject> named = subjects.findByUserIdAndHostIdAndIdIn(scope.userId(), scope.hostId(),
                        confirmed.stream().map(RadarSubjectProject::getSubjectId).distinct().toList()).stream()
                .filter(s -> s.getMergedIntoId() == null)
                .collect(Collectors.toMap(RadarSubject::getId, Function.identity()));
        Map<UUID, List<SubjectRef>> byProject = new LinkedHashMap<>();
        for (RadarSubjectProject row : confirmed) {
            RadarSubject subject = named.get(row.getSubjectId());
            if (subject != null) {
                byProject.computeIfAbsent(row.getWorkspaceId(), id -> new ArrayList<>())
                        .add(new SubjectRef(subject.getId(), subject.getName()));
            }
        }
        return byProject.entrySet().stream()
                .map(e -> new ProjectSubjectsView(e.getKey(), e.getValue().stream()
                        .sorted(Comparator.comparing(SubjectRef::name, String.CASE_INSENSITIVE_ORDER)).toList()))
                .toList();
    }

    /** Les projets du poste (terminaux exclus), sous l'isolation du périmètre. */
    @Transactional(readOnly = true)
    public List<Workspace> projectsOf(RadarScope scope) {
        return workspaceService.listByHost(scope.userId(), scope.hostId());
    }

    private Workspace requireProject(RadarScope scope, UUID workspaceId) {
        return projectsOf(scope).stream()
                .filter(w -> w.getId().equals(workspaceId))
                .findFirst()
                .orElseThrow(() -> new RadarNotFoundException("Projet introuvable sur ce poste."));
    }

    private SubjectProjectsView view(RadarScope scope, UUID subjectId) {
        List<Workspace> projects = projectsOf(scope);
        Map<UUID, RadarSubjectProject> rows = new HashMap<>();
        links.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), subjectId)
                .forEach(row -> rows.put(row.getWorkspaceId(), row));
        List<ProjectLinkView> linkViews = new ArrayList<>();
        List<ProjectCandidateView> candidates = new ArrayList<>();
        for (Workspace project : sorted(projects)) {
            RadarSubjectProject row = rows.get(project.getId());
            if (row != null && row.getState() != RadarSubjectProjectState.REFUSED) {
                linkViews.add(new ProjectLinkView(project.getId(), project.getName(), project.getProjectPath(),
                        row.getOrigin(), row.getState()));
            } else {
                candidates.add(new ProjectCandidateView(project.getId(), project.getName(), project.getProjectPath()));
            }
        }
        boolean inForge = spaceService.isActiveForOwner(scope.userId(), scope.hostId(), ClientSpace.FORGE);
        return new SubjectProjectsView(inForge, linkViews, candidates);
    }

    private static List<Workspace> sorted(List<Workspace> projects) {
        return projects.stream()
                .sorted(Comparator.comparing((Workspace w) -> w.getName() == null ? "" : w.getName(),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
