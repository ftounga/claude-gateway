package fr.claudegateway.rag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import fr.claudegateway.rag.provider.EmbeddingProperties;

/**
 * Active la liaison des propriétés du pipeline d'ingestion RAG (F-06). La {@code RestClient.Builder}
 * (utilisée par l'impl fournisseur HTTP) est fournie par l'auto-configuration Spring Boot.
 */
@Configuration
@EnableConfigurationProperties({ RagProperties.class, EmbeddingProperties.class })
public class RagConfig {
}
