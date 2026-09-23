package fr.claudegateway.atelier.resolution;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Le pool qui enregistre les résolutions après les tours (F-148 / SF-148-08).
 *
 * <p>Même choix que les pools de la carte (F-136), des sources de consigne (SF-148-06) et de l'index
 * (SF-148-07) : distinct des flux, petit, borné, rejet silencieux. <b>Aucun composant cluster</b>.</p>
 */
@Configuration
class ResolutionMemoryRefreshConfig {

    @Bean("resolutionMemoryExecutor")
    Executor resolutionMemoryExecutor(
            @Value("${app.atelier.resolution-memory.threads:2}") int threads,
            @Value("${app.atelier.resolution-memory.queue:32}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, threads));
        executor.setMaxPoolSize(Math.max(1, threads));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setKeepAliveSeconds(120);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("resolution-mem-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
