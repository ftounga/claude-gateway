package fr.claudegateway.atelier.promptsource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.PromptSource;
import fr.claudegateway.atelier.Workspace;

/**
 * Branche le cache des sources de la consigne sur la boucle d'atelier (F-148 / SF-148-06).
 *
 * <p><b>Les lectures sont synchrones</b> (une requête indexée en base, le chemin critique du tour),
 * <b>le rafraîchissement ne bloque jamais</b> : il part dans un pool dédié une fois la réponse
 * envoyée. Une consigne un peu ancienne est acceptable ; une seconde d'attente sur chaque demande ne
 * l'est pas. Une file pleine est ignorée en silence — le prochain tour relira.</p>
 */
@Component
public class PromptSourceCacheProvider implements PromptSource {

    private static final Logger log = LoggerFactory.getLogger(PromptSourceCacheProvider.class);

    private final PromptSourceStore store;
    private final Executor executor;

    public PromptSourceCacheProvider(PromptSourceStore store,
            @Qualifier("promptSourceRefreshExecutor") Executor executor) {
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
            // Un cache en panne coûte une lecture directe, jamais un tour raté.
            log.debug("État du cache de consigne indisponible ({})", ex.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public Optional<String> read(UUID userId, Workspace workspace, String path) {
        if (workspace == null) {
            return Optional.empty();
        }
        try {
            return store.read(userId, workspace.getId(), path);
        } catch (RuntimeException ex) {
            log.debug("Lecture de consigne en cache indisponible ({})", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public List<String> tree(UUID userId, Workspace workspace) {
        if (workspace == null) {
            return List.of();
        }
        try {
            return store.tree(userId, workspace.getId());
        } catch (RuntimeException ex) {
            log.debug("Arborescence en cache indisponible ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public void refreshAfterTurn(UUID userId, Workspace workspace, List<String> coreFiles,
            Predicate<String> skillPathFilter, int maxSkills) {
        if (userId == null || workspace == null || !workspace.isRunnerTarget()) {
            return; // Rien à précalculer hors cible RUNNER.
        }
        try {
            executor.execute(() -> {
                try {
                    store.refresh(userId, workspace, coreFiles, skillPathFilter, maxSkills);
                } catch (RuntimeException ex) {
                    log.debug("Sources de consigne non rafraîchies après le tour ({})",
                            ex.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException ex) {
            // File pleine, pool arrêté : le prochain tour relira. Rien à signaler à l'utilisateur.
            log.debug("Rafraîchissement de consigne non planifié ({})", ex.getClass().getSimpleName());
        }
    }
}
