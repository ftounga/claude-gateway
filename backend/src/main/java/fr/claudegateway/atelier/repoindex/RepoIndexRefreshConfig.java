package fr.claudegateway.atelier.repoindex;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Le pool qui relit les index de repo après les tours (F-148 / SF-148-07).
 *
 * <p>Même choix que les pools de la carte (F-136) et des sources de consigne (SF-148-06) : distinct
 * des flux, petit, borné, rejet silencieux. <b>Aucun composant cluster</b> : un pool de threads dans
 * le process existant.</p>
 */
@Configuration
class RepoIndexRefreshConfig {

    @Bean("repoIndexRefreshExecutor")
    Executor repoIndexRefreshExecutor(
            @Value("${app.atelier.repo-index.refresh-threads:2}") int threads,
            @Value("${app.atelier.repo-index.refresh-queue:32}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, threads));
        executor.setMaxPoolSize(Math.max(1, threads));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setKeepAliveSeconds(120);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("repo-index-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
