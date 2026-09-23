package fr.claudegateway.atelier;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implémentation JPA de {@link AtelierThreadStateStore} (F-121 / SF-121-10) : persiste le mode et le
 * plan sur l'entité {@link Workspace}, en filtrant {@code user_id}.
 *
 * <p>Charge une copie <b>fraîche</b> du workspace (plutôt que de réenregistrer l'instance détenue par
 * la boucle) : la boucle est non transactionnelle et d'autres écritures — la compaction (F-117) —
 * peuvent avoir avancé la frontière du fil pendant le tour. On ne touche donc que les deux colonnes
 * de mode/plan, sans risquer d'écraser un champ modifié entre-temps.</p>
 */
@Service
public class JpaAtelierThreadStateStore implements AtelierThreadStateStore {

    private static final Logger log = LoggerFactory.getLogger(JpaAtelierThreadStateStore.class);

    private final WorkspaceRepository workspaceRepository;

    public JpaAtelierThreadStateStore(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    @Transactional
    public void persist(UUID userId, UUID workspaceId, String mode, String planJson) {
        if (userId == null || workspaceId == null) {
            return;
        }
        try {
            // Isolation : findByIdAndUserId — un workspace d'autrui est introuvable, rien n'est écrit.
            workspaceRepository.findByIdAndUserId(workspaceId, userId).ifPresent(workspace -> {
                workspace.setChatThreadMode(mode);
                workspace.setChatThreadPlan(planJson);
                workspaceRepository.save(workspace);
            });
        } catch (RuntimeException ex) {
            // Best-effort : la mémoire du mode/plan ne doit jamais faire perdre la réponse du tour.
            log.debug("Persistance du mode/plan du thread ignorée (best-effort) : {}", ex.getMessage());
        }
    }
}
