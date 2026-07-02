package fr.claudegateway.chat;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Exécuteur borné dédié au relais SSE du chat en streaming (SF-02-04) : les flux longs tournent hors
 * du pool de threads servlet pour ne pas l'épuiser. Borné pour éviter l'emballement sous charge.
 */
@Configuration
class ChatStreamConfig {

    @Bean("chatStreamExecutor")
    Executor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("chat-sse-");
        executor.initialize();
        return executor;
    }
}
