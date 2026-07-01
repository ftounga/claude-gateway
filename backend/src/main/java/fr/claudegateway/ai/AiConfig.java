package fr.claudegateway.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Active la liaison des propriétés du fournisseur IA ({@link AnthropicProperties}).
 * La {@code RestClient.Builder} est fournie par l'auto-configuration Spring Boot.
 */
@Configuration
@EnableConfigurationProperties(AnthropicProperties.class)
public class AiConfig {
}
