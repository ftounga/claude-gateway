package fr.claudegateway.runner.host;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.RunnerShell;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.RunnerStatusService;
import fr.claudegateway.runner.audit.RunnerAudit;
import fr.claudegateway.runner.audit.RunnerAuditActivity;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.runner.host.dto.RunnerHostOverviewResponse;
import fr.claudegateway.runner.host.dto.RunnerHostOverviewResponse.HostProjectSummary;

/**
 * La <b>vue d'ensemble des postes</b> (F-49 / SF-49-01) : en une lecture, tous les postes d'un
 * utilisateur, leur état, les projets rangés dessous, et l'activité observée sur chacun.
 *
 * <p>F-48 a réuni les projets sous un poste, mais il n'existait aucun endroit d'où voir l'ensemble :
 * pour savoir si sa machine du bureau était encore connectée, l'utilisateur devait ouvrir un projet
 * qui vit dessus. Ce service rassemble ce qui vivait à trois endroits — la présence du runner, la
 * liste des projets, le journal de chacun.</p>
 *
 * <p><b>Une vue d'état, pas un flux</b> (arbitrage n° 3 du cadrage du 2026-09-10). L'écran rejoue cet
 * appel à intervalle ; il n'ouvre pas un canal par poste. Des flux vivants simultanés
 * multiplieraient les tours facturés pour un bénéfice que l'état couvre déjà.</p>
 *
 * <p><b>Isolation</b> : tout part de {@code user_id}. Les postes sont lus par propriétaire, les
 * projets par {@code user_id} + {@code host_id}, le journal par {@code user_id} + {@code host_id}.
 * Aucun identifiant ne vient d'un paramètre client — l'appel n'en prend aucun.</p>
 *
 * <p><b>D'où vient « ce qui tourne »</b> : du journal d'audit, en base, et non des appels en vol du
 * dispatcher — ceux-ci sont connus <b>par pod</b>, et sous HPA un écran servi par un replica ne
 * verrait rien de ce qui tourne sur l'autre. La contrepartie est assumée : l'activité est celle des
 * appels <b>terminés</b>, donc un tour qui vient de démarrer apparaît avec le retard de son premier
 * outil. Pour un suivi à la seconde près, il y a le terminal du projet — à un clic.</p>
 */
@Service
public class RunnerHostOverviewService {

    /** Plafond dur de la fenêtre observée : une vue d'état ne fait pas d'archéologie. */
    static final Duration MAX_OBSERVED_WINDOW = Duration.ofHours(24);

    private final RunnerHostService hostService;
    private final WorkspaceService workspaceService;
    private final RunnerStatusService statusService;
    private final RunnerAuditRepository auditRepository;
    private final Duration observedWindow;
    private final Duration activeWithin;

    public RunnerHostOverviewService(
            RunnerHostService hostService,
            WorkspaceService workspaceService,
            RunnerStatusService statusService,
            RunnerAuditRepository auditRepository,
            @Value("${app.runner.overview.observed-window:PT1H}") Duration observedWindow,
            @Value("${app.runner.overview.active-within:PT2M}") Duration activeWithin) {
        this.hostService = hostService;
        this.workspaceService = workspaceService;
        this.statusService = statusService;
        this.auditRepository = auditRepository;
        this.observedWindow = clamp(observedWindow, Duration.ofMinutes(1), MAX_OBSERVED_WINDOW);
        // Un projet ne peut pas être « actif » sur une fenêtre qu'on n'observe pas.
        this.activeWithin = clamp(activeWithin, Duration.ofSeconds(1), this.observedWindow);
    }

    /**
     * Vue d'ensemble des postes d'un utilisateur, du dernier vu au plus ancien, les jamais-vus
     * ensuite (par date de création décroissante).
     *
     * @param userId propriétaire — jamais un paramètre reçu du client
     */
    @Transactional(readOnly = true)
    public List<RunnerHostOverviewResponse> overview(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime since = now.minus(observedWindow);
        OffsetDateTime activeSince = now.minus(activeWithin);
        return hostService.list(userId).stream()
                .map(host -> describe(userId, host, since, activeSince))
                .sorted(BY_LAST_SEEN_THEN_CREATED)
                .toList();
    }

    // ------------------------------------------------------------------ interne

    /** Le dernier vu d'abord ; un poste jamais vu passe après, départagé par sa création. */
    private static final Comparator<RunnerHostOverviewResponse> BY_LAST_SEEN_THEN_CREATED =
            Comparator.comparing(RunnerHostOverviewResponse::lastSeenAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(RunnerHostOverviewResponse::createdAt,
                            Comparator.nullsLast(Comparator.reverseOrder()));

    private RunnerHostOverviewResponse describe(UUID userId, RunnerHost host, OffsetDateTime since,
            OffsetDateTime activeSince) {
        Map<UUID, RunnerAuditActivity> activity = activityByProject(userId, host.getId(), since);
        List<HostProjectSummary> projects = workspaceService.listByHost(userId, host.getId()).stream()
                .map(workspace -> summarize(userId, workspace, activity.get(workspace.getId()),
                        activeSince))
                .sorted(BY_ACTIVITY_THEN_NAME)
                .toList();

        return new RunnerHostOverviewResponse(
                host.getId(),
                host.getName(),
                host.getRootName(),
                host.getOs(),
                RunnerShell.fromDeclared(host.getShell()).map(RunnerShell::declared).orElse(null),
                host.getElevated(),
                statusService.statusOf(userId, host).connected(),
                host.getLastSeenAt(),
                host.getCreatedAt(),
                projects.stream()
                        .map(HostProjectSummary::lastActivityAt)
                        .filter(java.util.Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null),
                (int) projects.stream().filter(HostProjectSummary::active).count(),
                projects);
    }

    /** Le plus actif d'abord ; les muets ensuite, par ordre alphabétique — une liste stable. */
    private static final Comparator<HostProjectSummary> BY_ACTIVITY_THEN_NAME =
            Comparator.comparing(HostProjectSummary::lastActivityAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(HostProjectSummary::name,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    private Map<UUID, RunnerAuditActivity> activityByProject(UUID userId, UUID hostId,
            OffsetDateTime since) {
        Map<UUID, RunnerAuditActivity> byProject = new HashMap<>();
        for (RunnerAuditActivity row : auditRepository.aggregateActivityByHost(userId, hostId,
                since)) {
            byProject.put(row.getWorkspaceId(), row);
        }
        return byProject;
    }

    private HostProjectSummary summarize(UUID userId, Workspace workspace,
            RunnerAuditActivity activity, OffsetDateTime activeSince) {
        OffsetDateTime lastActivityAt = activity == null ? null : activity.getLastAt();
        boolean active = lastActivityAt != null && lastActivityAt.isAfter(activeSince);
        return new HostProjectSummary(
                workspace.getId(),
                workspace.getName(),
                workspace.getProjectPath(),
                workspace.getExecutionTarget() == null ? null
                        : workspace.getExecutionTarget().name(),
                lastActivityAt,
                // Un projet muet sur la fenêtre ne coûte aucune requête de plus.
                activity == null ? null : lastTool(userId, workspace.getId()),
                activity == null ? 0L : activity.getCalls(),
                active);
    }

    private String lastTool(UUID userId, UUID workspaceId) {
        return auditRepository.findFirstByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId,
                        workspaceId)
                .map(RunnerAudit::getTool)
                .orElse(null);
    }

    private static Duration clamp(Duration value, Duration min, Duration max) {
        Duration effective = Optional.ofNullable(value).orElse(min);
        if (effective.compareTo(min) < 0) {
            return min;
        }
        return effective.compareTo(max) > 0 ? max : effective;
    }
}
