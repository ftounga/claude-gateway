package fr.claudegateway.atelier.actions;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;

/**
 * <b>Le point du matin</b> (F-180 / SF-180-06, décision D6) : ce qui appelle un geste aujourd'hui — les
 * relances dues (F-175), les échéances du jour ou dépassées, les accès du registre qui expirent. En prod, la
 * relance n'était qu'affichée, et jamais vue.
 *
 * <p>Lecture seule. <b>Isolation</b> : le poste du terminal <b>possédé</b> (ou ce terminal s'il est
 * hébergé), {@code user_id} sur chaque lecture.</p>
 */
@Service
public class TerminalActionMorningService {

    /** Un accès qui expire dans ce nombre de jours, ou qui a expiré depuis au plus autant, est signalé. */
    static final int EXPIRY_WINDOW_DAYS = 7;

    private final TerminalActionRepository actions;
    private final AccesObtenuRepository acces;
    private final WorkspaceRepository workspaces;
    private final TerminalActionFollowUp followUp;
    private final Clock clock;

    public TerminalActionMorningService(TerminalActionRepository actions, AccesObtenuRepository acces,
                                        WorkspaceRepository workspaces, TerminalActionFollowUp followUp, Clock clock) {
        this.actions = actions;
        this.acces = acces;
        this.workspaces = workspaces;
        this.followUp = followUp;
        this.clock = clock;
    }

    /** Un accès qui expire, tel que la bande le dit. */
    public record ExpiringAcces(UUID id, String system, String account, LocalDate expiresAt, boolean expired) {
    }

    public record MorningPoint(List<TerminalActionResponse> followUps, List<TerminalActionResponse> dueToday,
                               List<TerminalActionResponse> overdue, List<ExpiringAcces> accesExpiring) {

        public boolean isEmpty() {
            return followUps.isEmpty() && dueToday.isEmpty() && overdue.isEmpty() && accesExpiring.isEmpty();
        }
    }

    @Transactional(readOnly = true)
    public MorningPoint morning(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaces.findByIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new TerminalActionNotFoundException("Terminal introuvable."));
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = now.atZoneSameInstant(TerminalActionResponse.ZONE).toLocalDate();

        List<TerminalAction> open = workspace.getHostId() != null
                ? actions.findByUserIdAndHostIdAndStatusIn(userId, workspace.getHostId(), TerminalActionStatus.OPEN_STATES)
                : actions.findByUserIdAndWorkspaceIdAndStatusInOrderByCreatedAtAsc(userId, workspace.getId(),
                        TerminalActionStatus.OPEN_STATES);
        Map<UUID, String> names = new HashMap<>();
        List<TerminalActionResponse> followUps = new ArrayList<>();
        List<TerminalActionResponse> dueToday = new ArrayList<>();
        List<TerminalActionResponse> overdue = new ArrayList<>();
        for (TerminalAction a : open) {
            boolean due = followUp.isDue(a, now);
            String state = TerminalActionResponse.dueStateOf(a, today);
            if (!due && !"TODAY".equals(state) && !"OVERDUE".equals(state)) {
                continue;
            }
            String name = names.computeIfAbsent(a.getWorkspaceId(),
                    id -> workspaces.findByIdAndUserId(id, userId).map(Workspace::getName).orElse("Projet supprimé"));
            TerminalActionResponse response = TerminalActionResponse.from(a, name, due);
            if (due) {
                followUps.add(response);
            }
            if ("TODAY".equals(state)) {
                dueToday.add(response);
            } else if ("OVERDUE".equals(state)) {
                overdue.add(response);
            }
        }

        List<AccesObtenu> registry = workspace.getHostId() != null
                ? acces.findByUserIdAndHostIdOrderByObtainedAtDesc(userId, workspace.getHostId())
                : acces.findByUserIdAndHostIdIsNullAndWorkspaceIdOrderByObtainedAtDesc(userId, workspace.getId());
        List<ExpiringAcces> expiring = registry.stream()
                .filter(a -> isInExpiryWindow(a.getExpiresAt(), today))
                .map(a -> new ExpiringAcces(a.getId(), a.getSystem(), a.getAccount(), a.getExpiresAt(),
                        a.isExpiredOn(today)))
                .toList();
        return new MorningPoint(followUps, dueToday, overdue, expiring);
    }

    /** Vrai si l'expiration tombe dans les {@value #EXPIRY_WINDOW_DAYS} jours, avant ou après aujourd'hui. */
    static boolean isInExpiryWindow(LocalDate expiresAt, LocalDate today) {
        return expiresAt != null && !expiresAt.isBefore(today.minusDays(EXPIRY_WINDOW_DAYS))
                && !expiresAt.isAfter(today.plusDays(EXPIRY_WINDOW_DAYS));
    }
}
