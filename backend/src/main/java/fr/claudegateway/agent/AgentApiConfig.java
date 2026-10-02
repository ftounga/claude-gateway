package fr.claudegateway.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enregistre les capacités d'API relayées par la boucle d'agent (F-172). */
@Configuration
@EnableConfigurationProperties(AgentApiFeaturesProperties.class)
public class AgentApiConfig {
}
