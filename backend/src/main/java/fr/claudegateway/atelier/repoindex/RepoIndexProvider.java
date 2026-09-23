package fr.claudegateway.atelier.repoindex;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.RepoIndex;
import fr.claudegateway.atelier.Workspace;

/**
 * Branche l'index de repo sur la boucle d'atelier (F-148 / SF-148-07).
 *
 * <p><b>Les lectures sont synchrones</b> (chemin critique du tour, requête indexée), <b>le
 * rafraîchissement ne bloque jamais</b> : il part dans un pool dédié une fois la réponse envoyée.
 * Repli passant partout : un index en panne coûte un {@code glob} en direct, jamais un tour raté.</p>
 */
@Component
public class RepoIndexProvider implements RepoIndex {

    private static final Logger log = LoggerFactory.getLogger(RepoIndexProvider.class);

    private final RepoIndexStore store;
    private final Executor executor;

    public RepoIndexProvider(RepoIndexStore store,
            @Qualifier("repoIndexRefreshExecutor") Executor executor) {
        this.store = store;
        this.executor = executor;
    }

    @Override
    public boolean isPrimed(UUID userId, Workspace workspace) {
        if (workspace == null) {
            return false;
        }
        try {
            return store.isPrimed(userId, workspace.getId());
        } catch (RuntimeException ex) {
            log.debug("État de l'index de repo indisponible ({})", ex.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public boolean indexed(UUID userId, Workspace workspace, String path) {
        if (workspace == null || path == null || path.isBlank()) {
            return false;
        }
        try {
            return store.contains(userId, workspace.getId(), path);
        } catch (RuntimeException ex) {
            // Index indisponible : « pas prouvé existant » ⇒ l'appelant reste en repli sûr.
            log.debug("Existence non vérifiée depuis l'index ({})", ex.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public Optional<String> glob(UUID userId, Workspace workspace, String pattern, String base) {
        if (workspace == null) {
            return Optional.empty();
        }
        try {
            return store.glob(userId, workspace.getId(), pattern, base);
        } catch (RuntimeException ex) {
            log.debug("glob depuis l'index indisponible ({})", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void refreshAfterTurn(UUID userId, Workspace workspace) {
        if (userId == null || workspace == null || !workspace.isRunnerTarget()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    store.refresh(userId, workspace);
                } catch (RuntimeException ex) {
                    log.debug("Index de repo non rafraîchi après le tour ({})",
                            ex.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException ex) {
            log.debug("Rafraîchissement d'index non planifié ({})", ex.getClass().getSimpleName());
        }
    }
}
