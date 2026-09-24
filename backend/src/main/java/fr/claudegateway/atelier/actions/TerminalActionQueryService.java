package fr.claudegateway.atelier.actions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;

/**
 * <b>La lecture qui traverse les projets</b> (F-154 / SF-154-03) : les actions ouvertes du compte,
 * avec le nom de leur projet.
 *
 * <p>Séparée de {@link TerminalActionService} à dessein : celui-ci porte la règle « un projet, ses
 * actions », avec {@code requireOwned} en tête de chaque méthode. Ici la lecture est <b>volontairement
 * transverse</b>, et le seul verrou est le {@code user_id} — le mélanger au service du terminal
 * brouillerait une garde qui doit rester lisible.</p>
 */
@Service
public class TerminalActionQueryService {

    /** Au-delà, ce n'est plus « ailleurs », c'est un inventaire : on coupe et on le dit à l'écran. */
    static final int MAX_ELSEWHERE = 100;

    private final TerminalActionRepository repository;
    private final WorkspaceRepository workspaces;

    public TerminalActionQueryService(TerminalActionRepository repository,
                                      WorkspaceRepository workspaces) {
        this.repository = repository;
        this.workspaces = workspaces;
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
        return repository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, TerminalActionStatus.OPEN)
                .stream()
                .filter(action -> !action.getWorkspaceId().equals(exclude))
                .limit(MAX_ELSEWHERE)
                .map(action -> TerminalActionElsewhereResponse.from(action,
                        names.computeIfAbsent(action.getWorkspaceId(), id -> nameOf(userId, id))))
                .toList();
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
