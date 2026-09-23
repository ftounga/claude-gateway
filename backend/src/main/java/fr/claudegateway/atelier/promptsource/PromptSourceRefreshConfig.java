package fr.claudegateway.atelier.promptsource;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Le pool qui relit les sources de la consigne après les tours (F-148 / SF-148-06).
 *
 * <p><b>Volontairement distinct</b> des pools de flux : ces tâches sont lentes (jusqu'à ~19
 * allers-retours vers la machine d'un client) et sans urgence. Les faire concourir avec les flux
 * ferait attendre un utilisateur pour une lecture d'arrière-plan. Même choix que le pool de la carte
 * (F-136).</p>
 *
 * <p><b>Petit et borné</b> : deux fils, une file courte, rejet silencieux — si la file est pleine,
 * des rafraîchissements sont déjà en cours et le prochain tour relira de toute façon. <b>Aucun
 * composant cluster</b> : un pool de threads dans le process existant.</p>
 */
@Configuration
class PromptSourceRefreshConfig {

    @Bean("promptSourceRefreshExecutor")
    Executor promptSourceRefreshExecutor(
            @Value("${app.atelier.prompt-source.refresh-threads:2}") int threads,
            @Value("${app.atelier.prompt-source.refresh-queue:32}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, threads));
        executor.setMaxPoolSize(Math.max(1, threads));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setKeepAliveSeconds(120);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("prompt-source-");
        // File pleine : on abandonne la tâche plutôt que de faire attendre le thread du tour qui vient
        // de répondre. Le prochain tour relira.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
