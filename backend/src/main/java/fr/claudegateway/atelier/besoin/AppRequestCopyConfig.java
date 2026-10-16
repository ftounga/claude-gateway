package fr.claudegateway.atelier.besoin;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Le pool qui copie les pièces des besoins (F-205 / SF-205-01). <b>Distinct</b> des pools de flux : une
 * copie de vidéo depuis un poste est lente et sans urgence. Petit et borné ; une file pleine rejette, et
 * le copieur marque alors les pièces en échec plutôt que de faire attendre le clic.
 */
@Configuration
class AppRequestCopyConfig {

    @Bean("appRequestCopyExecutor")
    Executor appRequestCopyExecutor(
            @Value("${app.besoins.copy-threads:2}") int threads,
            @Value("${app.besoins.copy-queue:32}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, threads));
        executor.setMaxPoolSize(Math.max(1, threads));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setKeepAliveSeconds(120);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("besoin-copy-");
        executor.initialize();
        return executor;
    }
}
