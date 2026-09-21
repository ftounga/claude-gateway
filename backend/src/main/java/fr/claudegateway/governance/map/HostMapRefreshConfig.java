package fr.claudegateway.governance.map;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Le pool qui relit les cartes après les tours (F-136 / SF-136-01).
 *
 * <p><b>Volontairement distinct</b> des pools de flux : ces tâches sont lentes (six allers-retours
 * vers la machine d'un client) et sans urgence. Les faire concourir avec les flux émetteurs
 * ferait attendre un utilisateur pour une lecture d'arrière-plan.</p>
 *
 * <p><b>Petit et borné</b> : deux fils, une file courte, et le rejet est silencieux — si la file est
 * pleine, c'est que des rafraîchissements sont déjà en cours, et le prochain tour relira de toute
 * façon.</p>
 */
@Configuration
class HostMapRefreshConfig {

    @Bean("hostMapRefreshExecutor")
    Executor hostMapRefreshExecutor(
            @Value("${app.governance.map.refresh-threads:2}") int threads,
            @Value("${app.governance.map.refresh-queue:32}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, threads));
        executor.setMaxPoolSize(Math.max(1, threads));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setKeepAliveSeconds(120);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("host-map-");
        // File pleine : on abandonne la tâche plutôt que de faire attendre l'appelant — qui est le
        // thread du tour qui vient de répondre.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
