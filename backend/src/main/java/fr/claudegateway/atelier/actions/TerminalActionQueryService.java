package fr.claudegateway.atelier.actions;

import java.time.Clock;
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
 * <b>Les lectures qui traversent les terminaux</b> (F-154 / SF-154-03, F-175 / SF-175-01) : les
 * attentes ouvertes du compte, et le tableau d'un poste.
 *
 * <p>Séparée de {@link TerminalActionService} à dessein : celui-ci porte la règle « un projet, ses
 * actions », avec {@code requireOwned} en tête de chaque méthode. Ici la lecture est <b>volontairement
 * transverse</b> — mais jamais entre comptes : le {@code user_id} est sur chaque requête, et le
 * {@code host_id} du tableau vient du terminal <b>possédé</b>, jamais du client.</p>
 */
@Service
public class TerminalActionQueryService {

    /** Au-delà, ce n'est plus « ailleurs », c'est un inventaire : on coupe et on le dit à l'écran. */
    static final int MAX_ELSEWHERE = 100;

    /** Combien de jours une attente fermée reste visible dans le tableau (« Rétablir »). */
    static final int RECENTLY_CLOSED_DAYS = 7;

    private final TerminalActionRepository repository;
    private final WorkspaceRepository workspaces;
    private final Clock clock;

    public TerminalActionQueryService(TerminalActionRepository repository,
                                      WorkspaceRepository workspaces,
                                      Clock clock) {
        this.repository = repository;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    /**
     * Les actions ouvertes du compte, hors le projet donné, les plus anciennes d'abord.
     *
     * @param excludeWorkspaceId projet à retirer (celui du terminal courant) ; ignoré s'il n'est pas
     *                           un identifiant lisible — un paramètre douteux ne doit pas faire 500
     */
    @Transactional(readOnly = true)
    public List<TerminalActionElsewhereResponse> openElsewhere(UUID userId, String excludeWorkspaceId) {
        UUID exclude = parse(excludeWorkspaceId);
        Map<UUID, String> names = new HashMap<>();
        return repository.findByUserIdAndStatusInOrderByCreatedAtAsc(userId, TerminalActionStatus.OPEN_STATES)
                .stream()
                .filter(action -> !action.getWorkspaceId().equals(exclude))
                .limit(MAX_ELSEWHERE)
                .map(action -> TerminalActionElsewhereResponse.from(action,
                        names.computeIfAbsent(action.getWorkspaceId(), id -> nameOf(userId, id))))
                .toList();
    }

    /**
     * <b>Le tableau des attentes vu d'un terminal</b> (F-175 / SF-175-01) : celles de ce terminal,
     * puis celles du reste de son poste. Un terminal hébergé (sans poste) ne voit que les siennes.
     *
     * @throws TerminalActionNotFoundException terminal inconnu ou d'un autre compte (404)
     */
    @Transactional(readOnly = true)
    public TerminalActionBoardResponse board(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaces.findByIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new TerminalActionNotFoundException("Terminal introuvable."));
        UUID hostId = workspace.getHostId();
        OffsetDateTime since = OffsetDateTime.now(clock).minusDays(RECENTLY_CLOSED_DAYS);

        List<TerminalAction> all = hostId == null
                ? repository.findBoardOfWorkspace(userId, workspaceId, TerminalActionStatus.OPEN_STATES, since)
                : repository.findBoardOfHost(userId, hostId, TerminalActionStatus.OPEN_STATES, since);

        Map<UUID, String> names = new HashMap<>();
        names.put(workspaceId, workspace.getName());
        List<TerminalActionResponse> here = new ArrayList<>();
        List<TerminalActionResponse> elsewhere = new ArrayList<>();
        int aFaire = 0;
        int demande = 0;
        OffsetDateTime oldest = null;
        for (TerminalAction action : all) {
            String name = names.computeIfAbsent(action.getWorkspaceId(), id -> nameOf(userId, id));
            if (action.getWorkspaceId().equals(workspaceId)) {
                here.add(TerminalActionResponse.from(action, name));
            } else if (elsewhere.size() < MAX_ELSEWHERE) {
                elsewhere.add(TerminalActionResponse.from(action, name));
            }
            if (action.getStatus() == TerminalActionStatus.A_FAIRE) {
                aFaire++;
            } else if (action.getStatus() == TerminalActionStatus.DEMANDE) {
                demande++;
            }
            if (action.isOpen() && (oldest == null || action.getCreatedAt().isBefore(oldest))) {
                oldest = action.getCreatedAt();
            }
        }
        return new TerminalActionBoardResponse(hostId, here, elsewhere, aFaire, demande, oldest);
    }

    /** Le nom du projet, relu <b>sous l'isolation</b> — jamais par identifiant seul. */
    private String nameOf(UUID userId, UUID workspaceId) {
        return workspaces.findByIdAndUserId(workspaceId, userId)
                .map(Workspace::getName)
                .orElse("Projet supprimé");
    }

    private static UUID parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
