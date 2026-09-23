package fr.claudegateway.atelier.resolution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.ResolutionMemory;
import fr.claudegateway.atelier.Workspace;

/**
 * Branche la mémoire de résolutions sur la boucle d'atelier (F-148 / SF-148-08).
 *
 * <p><b>Le rappel est synchrone</b> (chemin critique du tour : une requête indexée bornée + un match
 * en mémoire), <b>l'enregistrement ne bloque jamais</b> : il part dans un pool dédié une fois la
 * réponse envoyée. Repli passant partout. La mémoire est <b>par poste</b> : sans {@code host_id}
 * (cible SANDBOX), ni rappel ni enregistrement.</p>
 */
@Component
public class ResolutionMemoryProvider implements ResolutionMemory {

    private static final Logger log = LoggerFactory.getLogger(ResolutionMemoryProvider.class);

    private final ResolutionMemoryStore store;
    private final Executor executor;

    public ResolutionMemoryProvider(ResolutionMemoryStore store,
            @Qualifier("resolutionMemoryExecutor") Executor executor) {
        this.store = store;
        this.executor = executor;
    }

    @Override
    public Optional<String> recall(UUID userId, Workspace workspace, String question) {
        if (workspace == null || workspace.getHostId() == null) {
            return Optional.empty();
        }
        try {
            return store.recall(userId, workspace.getHostId(), question);
        } catch (RuntimeException ex) {
            log.debug("Rappel de résolution indisponible ({})", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void recordAfterTurn(UUID userId, Workspace workspace, String question, String conclusion,
            List<String> files) {
        if (userId == null || workspace == null || workspace.getHostId() == null) {
            return; // Mémoire par poste : sans host_id, rien à mémoriser.
        }
        UUID hostId = workspace.getHostId();
        UUID workspaceId = workspace.getId();
        try {
            executor.execute(() -> {
                try {
                    store.record(userId, hostId, workspaceId, question, conclusion, files);
                } catch (RuntimeException ex) {
                    log.debug("Résolution non enregistrée après le tour ({})",
                            ex.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException ex) {
            log.debug("Enregistrement de résolution non planifié ({})", ex.getClass().getSimpleName());
        }
    }
}
