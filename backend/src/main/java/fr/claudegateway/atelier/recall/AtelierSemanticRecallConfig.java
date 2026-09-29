package fr.claudegateway.atelier.recall;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import fr.claudegateway.rag.provider.EmbeddingProvider;

/**
 * Active la liaison des propriétés de la recherche sémantique du {@code recall} (F-162 / SF-162-06) et
 * fournit le pool qui calcule les embeddings des messages après les tours.
 *
 * <p>Même choix que les pools de la carte (F-136), des sources de consigne (SF-148-06) et de l'index de
 * repo (SF-148-07) : distinct des flux, petit, borné, rejet silencieux. Aucun composant cluster. La
 * {@code RestClient.Builder} (impl fournisseur HTTP) vient de l'auto-configuration Spring Boot.</p>
 */
@Configuration
@EnableConfigurationProperties(RecallSemanticProperties.class)
class AtelierSemanticRecallConfig {

    /**
     * Le fournisseur d'embeddings du recall, déclaré avec {@code defaultCandidate = false} : il n'est
     * candidat à l'injection par type <b>que</b> lorsqu'un qualifieur le nomme
     * ({@code @Qualifier("recallEmbeddingProvider")}). Cela laisse intacte l'injection par type du RAG
     * documentaire (qui injecte {@code EmbeddingProvider} sans qualifieur et n'a qu'un bean actif). La
     * {@code RestClient.Builder} vient de l'auto-configuration Spring Boot.
     */
    @Bean(defaultCandidate = false)
    EmbeddingProvider recallEmbeddingProvider(RecallSemanticProperties properties,
            RestClient.Builder builder) {
        return new OpenAiRecallEmbeddingProvider(properties, builder);
    }

    @Bean("atelierEmbeddingExecutor")
    Executor atelierEmbeddingExecutor(
            @Value("${app.atelier.recall.semantic.embed-threads:2}") int threads,
            @Value("${app.atelier.recall.semantic.embed-queue:64}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, threads));
        executor.setMaxPoolSize(Math.max(1, threads));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setKeepAliveSeconds(120);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("atelier-embed-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
